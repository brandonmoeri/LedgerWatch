// Viewer-request function on the default (S3) behavior.
// Client-side routes such as /accounts/123 have no file behind them, so serve
// index.html and let React Router resolve the path. Requests for real assets
// (anything whose last path segment has an extension) pass through untouched.
function handler(event) {
  var request = event.request;
  var lastSegment = request.uri.substring(request.uri.lastIndexOf('/') + 1);

  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }

  return request;
}
