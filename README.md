# LedgerWatch

A banking microservice platform for tracking accounts and transactions, built as a learning/portfolio project demonstrating production-style microservice architecture on the JVM with a modern React frontend.

## Current state

| Component | Status |
|---|---|
| `account-service` (Spring Boot 3.3, Java 21) | **Functional** — full account CRUD REST API, JPA persistence, validation, unit + integration tests |
| `transaction-service` (Spring Boot 3.3, Java 21) | **Functional** — full transaction CRUD REST API, JPA persistence, validation, unit + integration tests |
| Postgres + both services (local, via Docker Compose) | Running — `accounts` and `transactions` schemas owned by per-service Flyway migrations |
| React/TS frontend (Vite, React 19, Redux Toolkit) | **In progress** — JWT login, dashboard (Recharts balance-over-time and spend-by-type), accounts list/detail/create, and transactions pages; Redux Toolkit store; Axios API client; Storybook; Vitest + Testing Library + MSW + jest-axe tests |
| CI/CD | **CI functional** — GitHub Actions on PRs to `master`/`dev`: path-filtered per-service Spotless check + `mvn verify` (JaCoCo 70% coverage gate), frontend type check + lint + Vitest; Docker images published to GHCR on push to `dev`. AWS access from Actions via OIDC (no stored keys); deployment (CD) not started |
| AWS infra | IaC ready (Terraform modules, `infra/aws/`) for both services on ECS Fargate + RDS. `account-service` was deployed and CRUD-verified on the earlier single-service config, then torn down; the two-service config has been planned but not yet applied |

## Architecture

Two separately deployable Spring Boot services, each owning its own Postgres schema — no shared-database shortcut. Inter-service communication starts as REST and is a candidate for event-driven (outbox + queue) once a real consistency problem exists.

- **`account-service`** (`:8081`) — account identity, balances, and lifecycle. Source of truth for account state.
- **`transaction-service`** (`:8082`) — transaction history and posting logic; will validate against `account-service` rather than touching its schema directly.
- **`frontend`** — React + TypeScript SPA wired to `account-service`; Redux Toolkit for state, React Router for navigation.

Planned infra path: local Postgres → AWS RDS → Aurora → EKS → Lambda (event-driven workloads).

## Repository layout

```
LedgerWatch/
├── account-service/          # Spring Boot — account identity & balances
│   └── Dockerfile            # Multi-stage build (Maven reactor → JRE runtime image)
├── transaction-service/      # Spring Boot — transaction posting & history
│   └── Dockerfile            # Multi-stage build (Maven reactor → JRE runtime image)
├── frontend/                 # React/TS SPA — Vite, Redux Toolkit, React Router
├── docker/postgres/init.sql  # Schema seed (accounts + transactions schemas)
├── docker-compose.yml        # Postgres + account-service + transaction-service
├── infra/aws/                # Terraform — AWS deployment (network, alb, ecs-service, rds, frontend modules)
└── pom.xml                   # Maven parent (Java 21, Spring Boot 3.3.4)
```

## Getting started

```bash
# Copy env template and fill in JWT_SECRET (32+ random bytes)
cp .env.example .env

# Start Postgres + account-service + transaction-service
docker compose up -d --build

# Start the frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

To run a service outside its container (e.g. for debugging), stop it in Compose and run it directly from the repo root: `mvn -pl account-service spring-boot:run` or `mvn -pl transaction-service spring-boot:run`.

## APIs

**`account-service`** (`http://localhost:8081`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/accounts` | List all accounts |
| `GET` | `/accounts/{id}` | Fetch account by UUID |
| `POST` | `/accounts` | Create account (`ownerName`, optional `initialBalance`) |
| `PATCH` | `/accounts/{id}` | Update `ownerName` and/or `status` |

Account statuses: `ACTIVE`, `FROZEN`, `CLOSED`.

**`transaction-service`** (`http://localhost:8082`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/transactions` | List all transactions |
| `GET` | `/transactions/{id}` | Fetch transaction by UUID |
| `POST` | `/transactions` | Accept a transaction (`accountId`, `type`, `amount`, optional `description`) and return `202` with status `PENDING`; send an `Idempotency-Key` header to make retries safe |
| `PATCH` | `/transactions/{id}` | Update `description`, or void a `POSTED` transaction |

Transaction types: `CREDIT`, `DEBIT`. Statuses: `PENDING`, `POSTED`, `REJECTED`, `VOIDED`. The balance is applied asynchronously: poll `GET /transactions/{id}` until the status is `POSTED` or `REJECTED`, which comes with a `rejectionReason` (`ACCOUNT_NOT_FOUND`, `ACCOUNT_NOT_ACTIVE` or `INSUFFICIENT_FUNDS`).

## AWS deployment

Terraform config in `infra/aws/` deploys `account-service` and `transaction-service` from one shared `ecs-service` module: ECR → ECS Fargate → RDS Postgres, with DB credentials and the JWT key via Secrets Manager. An Application Load Balancer is the public entry point: `/accounts` and `/auth` go to `account-service`, `/transactions*` to `transaction-service`, and any other path gets a 404, so `/internal/*` isn't exposed. It listens on plain HTTP only. The React SPA is served from a private S3 bucket through CloudFront over HTTPS. CloudFront also forwards `/api/*` to the ALB with the `/api` prefix stripped, the same as the Vite dev proxy, so the browser talks to a single origin and no CORS is needed. `transaction-service` reaches `account-service` over Cloud Map private DNS. No NAT Gateway (cost-minimized).

### Remote state

Terraform state lives in S3 (`ledgerwatch/terraform.tfstate`), so it survives a lost laptop and is locked during writes. Locking uses S3 native lockfiles (`use_lockfile`), so there's no DynamoDB table; this needs Terraform ≥ 1.11. The bucket can't be managed through the backend it hosts, so `infra/aws/bootstrap/` creates it, keeping its own small state locally.

Do this once per AWS account, from `infra/aws/`:

```bash
# 1. Grant your IAM user access to the state bucket and the GitHub OIDC resources (needs IAM admin rights).
aws iam put-user-policy --user-name <your-iam-user> --policy-name ledgerwatch-tfstate --policy-document file://bootstrap/operator-policy.json

# 2. Create the bucket: ledgerwatch-tfstate-<account-id>
terraform -chdir=bootstrap init
terraform -chdir=bootstrap apply

# 3. Point this config at it. -migrate-state copies any existing local state across.
terraform init -migrate-state -backend-config="bucket=$(terraform -chdir=bootstrap output -raw state_bucket)"

# 4. Confirm the state is remote, then remove the local copies, which can hold secrets in plaintext.
terraform state pull
rm terraform.tfstate*
```

On a new machine or clone, skip all of that and pass the bucket name directly: `terraform init -backend-config="bucket=ledgerwatch-tfstate-<account-id>"`. The bootstrap's local state isn't needed for normal use. If it's lost, `terraform -chdir=bootstrap import aws_s3_bucket.state <bucket>` recovers the bucket, and the same goes for the other resources in `bootstrap/main.tf`.

If a crashed run leaves a stale lock, `terraform force-unlock <lock-id>` clears it (the ID is in the error message). To undo a bad state write, restore an earlier object version: they're kept for 90 days.

### CI access (GitHub OIDC)

The bootstrap also creates a GitHub OIDC identity provider and a `ledgerwatch-github-deploy` IAM role, so GitHub Actions gets short-lived AWS credentials and no access keys are stored in the repo. Only workflows triggered by a push to `master` or `dev` in this repo can assume the role; pull requests can't. Its permissions cover the deploy steps below (ECR push, ECS service rollout, S3 upload, CloudFront invalidation), not `terraform apply`. See [ARCHITECTURE.md](ARCHITECTURE.md#ci-access-to-aws-github-oidc) for the trust policy.

After `terraform -chdir=bootstrap apply`, store the role ARN as a repository **variable** (Settings → Secrets and variables → Actions → Variables). It isn't a secret:

```bash
terraform -chdir=bootstrap output -raw github_deploy_role_arn   # → AWS_DEPLOY_ROLE_ARN
```

To check it end to end, run the **AWS OIDC Check** workflow from the Actions tab on `master` or `dev`. It should print the assumed-role ARN.

### Deploying

Requires the [remote state](#remote-state) bootstrap above.

```bash
cd infra/aws
terraform apply -target=module.account_service.aws_ecr_repository.this -target=module.transaction_service.aws_ecr_repository.this

# build & push both images from the repo root (repo URLs: terraform output ecr_repository_urls)
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com
docker build -f account-service/Dockerfile -t <account-service-repo-url>:latest .
docker build -f transaction-service/Dockerfile -t <transaction-service-repo-url>:latest .
docker push <account-service-repo-url>:latest
docker push <transaction-service-repo-url>:latest

terraform apply -var "admin_cidr=<your-ip>/32" -target=module.rds
docker run --rm -i postgres:16-alpine psql "postgresql://ledger:<db_password>@<rds_endpoint>:5432/ledgerwatch?sslmode=require" < docker/postgres/init.sql  # from the repo root
terraform apply -var "admin_cidr=<your-ip>/32"
curl -i "$(terraform output -raw alb_url)/accounts"  # 401 from account-service means routing works
```

Then publish the SPA (from the repo root). Hashed assets are cached for a year; `index.html` is revalidated on every request, so a new deploy is visible without waiting on the CDN:

```bash
(cd frontend && npm ci && npm run build)
BUCKET=$(terraform -chdir=infra/aws output -raw frontend_bucket)
aws s3 sync frontend/dist "s3://$BUCKET" --delete --exclude index.html --cache-control "public,max-age=31536000,immutable"
aws s3 cp frontend/dist/index.html "s3://$BUCKET/index.html" --cache-control "no-cache"

URL=$(terraform -chdir=infra/aws output -raw frontend_url)   # new distributions take a few minutes to deploy
curl -sI "$URL/accounts/does-not-matter" | head -1        # 200: SPA fallback serves index.html
curl -s  "$URL/api/accounts" -o /dev/null -w '%{http_code}
'  # 401: /api reaches account-service
TOKEN=$(curl -s "$URL/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"analyst","password":"analyst123"}' | jq -r .token)
curl -s "$URL/api/accounts" -H "Authorization: Bearer $TOKEN" -o /dev/null -w '%{http_code}
'  # 200: Authorization survives CloudFront on GET
```

`aws s3 sync --delete` removes the old hashed bundles; a cached `index.html` from a previous deploy can't outlive its `no-cache` revalidation, so no invalidation is needed. If you ever upload without those headers, run `aws cloudfront create-invalidation --distribution-id "$(terraform -chdir=infra/aws output -raw frontend_distribution_id)" --paths '/*'`.

**Continuous deployment:** once the stack is up, [.github/workflows/deploy.yml](.github/workflows/deploy.yml) repeats the image and SPA steps above on every push to `master`: it builds both images, pushes `:latest` and `:<sha>` to ECR, force-redeploys both ECS services and waits until they're stable, then uploads the SPA and invalidates CloudFront. It needs three repository variables: `AWS_DEPLOY_ROLE_ARN` (bootstrap output `github_deploy_role_arn`), `FRONTEND_DISTRIBUTION_ID` (output `frontend_distribution_id`), and `DEPLOY_ENABLED=true`. Unset `DEPLOY_ENABLED` after tearing down so merges skip the deploy rather than fail.

### Operations

The stack has three states, and the right one depends on whether anyone needs to reach the live URL without warning:

| State | Cost | Live URL | Time to full service |
|---|---|---|---|
| **Running** | ~$88/mo (~$2.90/day) | Works | — |
| **Paused** (tasks at 0, RDS stopped) | ~$28/mo | Same URL; the SPA loads but every API call fails | ~10 min |
| **Torn down** | < $0.10/mo | Gone; a new CloudFront URL is created on the next bring-up | ~30 min, partly manual |

#### Monthly cost estimate

These are us-east-1 on-demand list prices with no free tier, at 730 hours a month and portfolio-level traffic:

| Item | Running | Paused | Notes |
|---|---|---|---|
| Fargate, 2 tasks × 0.5 vCPU / 1 GB | $36.04 | $0 | `cpu`/`memory` defaults in `modules/ecs-service` |
| Public IPv4 addresses, $3.65 each | $18.25 (5) | $7.30 (2) | 2 tasks + 2 ALB nodes + RDS. Easy to forget: it's the second-largest line |
| Application Load Balancer | ~$17 | ~$17 | Hourly charge plus a near-idle LCU charge. Doesn't scale down: this is the floor while the stack exists |
| RDS `db.t4g.micro` + 20 GB gp3 | $13.98 | $2.30 | Compute $11.68 + storage $2.30. A stopped instance is billed for storage only |
| Secrets Manager (2 secrets), Cloud Map / Route 53 private zone, ECR, CloudWatch Logs | ~$2 | ~$2 | ECR grows with every deploy's `:<sha>` tag (no lifecycle policy yet) |
| S3 + CloudFront | ~$0 | ~$0 | Within CloudFront's always-free tier |
| Terraform state bucket | < $0.10 | < $0.10 | Survives teardown (`prevent_destroy`) |
| **Total** | **~$88** | **~$28** | |

A rolling deploy briefly runs a third task, which costs fractions of a cent. To check the estimate against the bill, look at Cost Explorer grouped by service.

#### Pausing (scale to zero)

This keeps every resource and URL, and stops the compute charges. Run it from anywhere that has the operator credentials. Stop the services before the database:

```bash
for s in account-service transaction-service; do
  aws ecs update-service --cluster ledgerwatch-cluster --service ledgerwatch-$s --desired-count 0 --query service.desiredCount
done
aws rds stop-db-instance --db-instance-identifier ledgerwatch-db --query DBInstance.DBInstanceStatus
```

To resume, start the database first, because the services run Flyway against it on startup:

```bash
aws rds start-db-instance --db-instance-identifier ledgerwatch-db --query DBInstance.DBInstanceStatus
aws rds wait db-instance-available --db-instance-identifier ledgerwatch-db
for s in account-service transaction-service; do
  aws ecs update-service --cluster ledgerwatch-cluster --service ledgerwatch-$s --desired-count 1 --query service.desiredCount
done
aws ecs wait services-stable --cluster ledgerwatch-cluster --services ledgerwatch-account-service ledgerwatch-transaction-service
```

Caveats:
- **RDS restarts itself after 7 days stopped** and goes back to full billing. Re-run `stop-db-instance` weekly, or tear down instead.
- `desired_count` is managed by Terraform (default `1`), so the next `terraform apply` resumes the services. That's harmless, but expect it.
- A merge to `master` while paused still deploys. The images update and nothing starts, which is fine.

#### Tearing down

1. Turn off continuous deployment first, so a merge during or after teardown is skipped instead of failing: `gh variable delete DEPLOY_ENABLED`.
2. From `infra/aws/`, run `terraform destroy -var "admin_cidr=<your-ip>/32"`.
3. Check that nothing is still billing. Every command should print nothing, apart from resources this config doesn't own:
   ```bash
   aws ecs list-clusters --query 'clusterArns[?contains(@, `ledgerwatch`)]' --output text
   aws rds describe-db-instances --query 'DBInstances[].DBInstanceIdentifier' --output text
   aws elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerName' --output text
   aws secretsmanager list-secrets --query 'SecretList[].Name' --output text
   ```
   The RDS and Secrets Manager checks are unfiltered on purpose: a hand-made instance outside Terraform won't be destroyed by step 2.

What's lost: all data in RDS (there are no backups and no final snapshot; the demo logins live in code, so they still work afterwards), every ECR image (`force_delete`), and the CloudFront and ALB hostnames. What survives: the state bucket and the bootstrap's GitHub OIDC role, both close to free.

**Bringing it back** is the [Deploying](#deploying) section from the top. Afterwards:
- Set `FRONTEND_DISTRIBUTION_ID` to the new `terraform output -raw frontend_distribution_id`, because the old distribution ID no longer exists.
- Set `DEPLOY_ENABLED=true` again.
- Update any link you've shared, because the `*.cloudfront.net` hostname is new.

#### Budget alert

AWS Budgets is free for the first two budgets. This one emails when actual spend passes $80 of a $100 monthly budget, which a running stack nears by the end of a month:

```bash
aws budgets create-budget --account-id "$(aws sts get-caller-identity --query Account --output text)" \
  --budget '{"BudgetName":"ledgerwatch-monthly","BudgetLimit":{"Amount":"100","Unit":"USD"},"TimeUnit":"MONTHLY","BudgetType":"COST"}' \
  --notifications-with-subscribers '[{"Notification":{"NotificationType":"ACTUAL","ComparisonOperator":"GREATER_THAN","Threshold":80,"ThresholdType":"PERCENTAGE"},"Subscribers":[{"SubscriptionType":"EMAIL","Address":"<your-email>"}]}]'
```

The resources above are not currently running. The Deploying section is the reproducible path to bring them back up.

## Running tests

```bash
mvn -pl account-service test
```

## Roadmap

- [x] Account CRUD + balance logic in `account-service`
- [x] Transaction posting + history in `transaction-service`
- [ ] `transaction-service` validates accounts via REST call to `account-service`
- [x] React/TS/Redux frontend scaffold with Storybook
- [x] Unit/component tests (Vitest + Testing Library + MSW)
- [ ] Cypress E2E against local stack
- [x] GitHub Actions: build, test, lint on PR
- [x] GitHub Actions: publish service images to GHCR
- [ ] Jenkins: image build + deploy to AWS
- [x] Migrate local Postgres → AWS RDS (`account-service`, Terraform; verified then torn down)
- [ ] Migrate AWS RDS → Aurora
- [ ] Containerize services onto EKS
- [ ] Extract event-driven workloads to Lambda
