locals {
  alb_namespace = "AWS/ApplicationELB"
  lb            = var.load_balancer_arn_suffix

  # Micrometer's CloudWatch registry publishes time gauges in its base time unit,
  # milliseconds, whatever unit the gauge was built with. /actuator/metrics shows
  # seconds; CloudWatch has ms.
  outbox_lag_metric     = "ledgerwatch.outbox.lag.value"
  outbox_pending_metric = "ledgerwatch.outbox.pending.value"
  app_dimension         = ["application", var.metrics_application]

  # IF() keeps a quiet minute from reading as a high error rate, and FILL() turns
  # the ALB's "no data" (it reports counts only when nonzero) into 0.
  http_5xx_rate_expression = "IF(FILL(requests, 0) >= ${var.http_5xx_min_requests}, 100 * FILL(errors, 0) / FILL(requests, 0), 0)"

  alarm_actions = [aws_sns_topic.alarms.arn]
}

resource "aws_sns_topic" "alarms" {
  name = "${var.project}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count = var.alarm_email != "" ? 1 : 0

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ---------------------------------------------------------------------------
# Alarms
# ---------------------------------------------------------------------------

# 5xx answered by the service itself (an unhandled exception, or a 503 from an
# open circuit breaker), as a share of the requests routed to it.
resource "aws_cloudwatch_metric_alarm" "service_5xx_rate" {
  for_each = var.target_group_arn_suffixes

  alarm_name        = "${var.project}-${each.key}-5xx-rate"
  alarm_description = "More than ${var.http_5xx_rate_threshold}% of ${each.key}'s requests returned 5xx over 5 minutes (at least ${var.http_5xx_min_requests} requests). Check its log group /ecs/${var.project}-${each.key} for status >= 500."

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.http_5xx_rate_threshold
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  metric_query {
    id          = "rate"
    label       = "${each.key} 5xx rate (%)"
    expression  = local.http_5xx_rate_expression
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      namespace   = local.alb_namespace
      metric_name = "HTTPCode_Target_5XX_Count"
      stat        = "Sum"
      period      = 300
      dimensions = {
        LoadBalancer = local.lb
        TargetGroup  = each.value
      }
    }
  }

  metric_query {
    id = "requests"
    metric {
      namespace   = local.alb_namespace
      metric_name = "RequestCount"
      stat        = "Sum"
      period      = 300
      dimensions = {
        LoadBalancer = local.lb
        TargetGroup  = each.value
      }
    }
  }
}

# 5xx the load balancer generates itself: 503 when a target group has no healthy
# targets, 502/504 when a task refuses or times out. A count, not a rate: requests
# rejected before a target is chosen are left out of RequestCount, so during a
# full outage the rate's denominator is 0 and a rate alarm would never fire.
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name        = "${var.project}-alb-5xx"
  alarm_description = "The load balancer itself returned ${var.alb_5xx_count_threshold}+ 5xx in 5 minutes: usually no healthy tasks behind a target group, or tasks timing out. Check the ECS services' events and target health. Expected if someone calls the API while the stack is paused."

  namespace   = local.alb_namespace
  metric_name = "HTTPCode_ELB_5XX_Count"
  statistic   = "Sum"
  period      = 300
  dimensions = {
    LoadBalancer = local.lb
  }

  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.alb_5xx_count_threshold
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# Lag grows steadily while events can't be delivered (account-service down,
# publisher stalled), however few are waiting. Parked events don't count.
# Missing data (no transaction-service task pushing, e.g. while paused) leaves
# the alarm in INSUFFICIENT_DATA rather than guessing.
resource "aws_cloudwatch_metric_alarm" "outbox_lag" {
  alarm_name        = "${var.project}-outbox-lag"
  alarm_description = "The oldest undelivered outbox event has been waiting more than ${var.outbox_lag_threshold_seconds}s for 3 minutes. Check account-service health and OutboxPublisher lines in /ecs/${var.project}-transaction-service."

  namespace   = var.metrics_namespace
  metric_name = local.outbox_lag_metric
  statistic   = "Maximum"
  period      = 60
  dimensions = {
    application = var.metrics_application
  }

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.outbox_lag_threshold_seconds * 1000 # published in ms
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  treat_missing_data  = "missing"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
}

# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------

locals {
  services = keys(var.target_group_arn_suffixes)

  tg_metric = { for name, tg in var.target_group_arn_suffixes : name => ["TargetGroup", tg, "LoadBalancer", local.lb] }

  widget_defaults = {
    region = var.aws_region
    view   = "timeSeries"
  }

  widgets = [
    {
      type   = "alarm"
      x      = 0
      y      = 0
      width  = 24
      height = 3
      properties = {
        title = "Alarms"
        alarms = concat(
          [for alarm in aws_cloudwatch_metric_alarm.service_5xx_rate : alarm.arn],
          [aws_cloudwatch_metric_alarm.alb_5xx.arn, aws_cloudwatch_metric_alarm.outbox_lag.arn],
        )
      }
    },
    {
      type   = "metric"
      x      = 0
      y      = 3
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title  = "5xx rate by service (%)"
        period = 300
        yAxis  = { left = { min = 0, showUnits = false } }
        # Same expression as the alarms, with per-service query ids.
        metrics = concat(
          [for i, name in local.services : concat([local.alb_namespace, "HTTPCode_Target_5XX_Count"], local.tg_metric[name], [{ id = "errors${i}", stat = "Sum", visible = false }])],
          [for i, name in local.services : concat([local.alb_namespace, "RequestCount"], local.tg_metric[name], [{ id = "requests${i}", stat = "Sum", visible = false }])],
          [for i, name in local.services : [{
            id         = "rate${i}"
            label      = name
            expression = replace(replace(local.http_5xx_rate_expression, "errors", "errors${i}"), "requests", "requests${i}")
          }]],
        )
        annotations = {
          horizontal = [{ label = "Alarm", value = var.http_5xx_rate_threshold }]
        }
      })
    },
    {
      type   = "metric"
      x      = 12
      y      = 3
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title   = "Requests by service"
        stat    = "Sum"
        period  = 60
        yAxis   = { left = { min = 0, showUnits = false } }
        metrics = [for name in local.services : concat([local.alb_namespace, "RequestCount"], local.tg_metric[name], [{ label = name }])]
      })
    },
    {
      type   = "metric"
      x      = 0
      y      = 9
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title  = "5xx responses"
        stat   = "Sum"
        period = 60
        yAxis  = { left = { min = 0, showUnits = false } }
        metrics = concat(
          [for name in local.services : concat([local.alb_namespace, "HTTPCode_Target_5XX_Count"], local.tg_metric[name], [{ label = "${name} (target)" }])],
          [[local.alb_namespace, "HTTPCode_ELB_5XX_Count", "LoadBalancer", local.lb, { label = "Load balancer" }]],
        )
      })
    },
    {
      type   = "metric"
      x      = 12
      y      = 9
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title   = "Target response time p95 (s)"
        stat    = "p95"
        period  = 60
        yAxis   = { left = { min = 0, showUnits = false } }
        metrics = [for name in local.services : concat([local.alb_namespace, "TargetResponseTime"], local.tg_metric[name], [{ label = name }])]
      })
    },
    {
      type   = "metric"
      x      = 0
      y      = 15
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title  = "Outbox lag (s)"
        period = 60
        yAxis  = { left = { min = 0, showUnits = false } }
        metrics = [
          concat([var.metrics_namespace, local.outbox_lag_metric], local.app_dimension, [{ id = "lagms", stat = "Maximum", visible = false }]),
          [{ id = "lag", label = "Oldest undelivered event", expression = "lagms / 1000" }],
        ]
        annotations = {
          horizontal = [{ label = "Alarm", value = var.outbox_lag_threshold_seconds }]
        }
      })
    },
    {
      type   = "metric"
      x      = 12
      y      = 15
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title   = "Outbox events waiting"
        stat    = "Maximum"
        period  = 60
        yAxis   = { left = { min = 0, showUnits = false } }
        metrics = [concat([var.metrics_namespace, local.outbox_pending_metric], local.app_dimension, [{ label = "Undelivered" }])]
      })
    },
    {
      type   = "metric"
      x      = 0
      y      = 21
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title   = "Transactions posted / min"
        stat    = "Sum"
        period  = 60
        stacked = true
        yAxis   = { left = { min = 0, showUnits = false } }
        metrics = [
          for type in ["CREDIT", "DEBIT"] :
          concat([var.metrics_namespace, "ledgerwatch.transactions.posted.count"], local.app_dimension, ["type", type, { label = type }])
        ]
      })
    },
    {
      type   = "metric"
      x      = 12
      y      = 21
      width  = 12
      height = 6
      properties = merge(local.widget_defaults, {
        title   = "Transactions rejected / min"
        stat    = "Sum"
        period  = 60
        stacked = true
        yAxis   = { left = { min = 0, showUnits = false } }
        metrics = [
          for reason in ["ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_ACTIVE", "INSUFFICIENT_FUNDS"] :
          concat([var.metrics_namespace, "ledgerwatch.transactions.rejected.count"], local.app_dimension, ["reason", reason, { label = reason }])
        ]
      })
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.project
  dashboard_body = jsonencode({ widgets = local.widgets })
}
