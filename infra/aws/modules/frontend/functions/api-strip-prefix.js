// Viewer-request function on the /api/* behavior.
// The SPA calls /api/accounts, /api/auth/login, ... (same as the Vite dev proxy),
// but the ALB routes the bare service paths, so drop the /api prefix.
function handler(event) {
  var request = event.request;
  request.uri = request.uri.replace(/^\/api(?=\/|$)/, '') || '/';
  return request;
}
