/** 静态资源路径：兼容部署到 /interview/ 这类子路径 */
export function asset(path) {
  const clean = path.replace(/^\//, '')
  return `${import.meta.env.BASE_URL}${clean}`
}
