/**
 * Bearer token authentication middleware
 */
export function authMiddleware(c, next) {
  const authHeader = c.req.header('Authorization');
  const token = process.env.AUTH_TOKEN;

  if (!token) {
    return c.json({ error: 'Server misconfigured: no AUTH_TOKEN set' }, 500);
  }

  if (!authHeader || !authHeader.startsWith('Bearer ') || authHeader.slice(7) !== token) {
    return c.json({ error: 'Unauthorized' }, 401);
  }

  return next();
}
