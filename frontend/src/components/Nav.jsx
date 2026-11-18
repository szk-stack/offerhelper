import { NavLink, Link, useNavigate } from 'react-router-dom'
import { useTheme } from '../context/ThemeContext'
import { useAuth } from '../context/AuthContext'
import { asset } from '../utils/asset'
import './Nav.css'

const navItems = [
  { path: '/', label: '首页' },
  { path: '/resumes', label: '简历+投递' },
  { path: '/interviews', label: '面经搜索' },
  { path: '/mock-interview', label: '结构化面试' },
  { path: '/replay', label: '面试复盘' },
]

export default function Nav() {
  const { theme, toggleTheme } = useTheme()
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <nav className="nav">
      <div className="nav-brand">
        <NavLink to="/">
          <img src={asset('logo.png')} alt="sspOffer" className="nav-logo" />
          <span className="nav-brand-text">sspOffer面经助手</span>
        </NavLink>
      </div>
      <div className="nav-right">
        <button
          className="theme-toggle"
          onClick={toggleTheme}
          title={theme === 'dark' ? '切换为浅色模式' : '切换为深色模式'}
          aria-label={theme === 'dark' ? '切换为浅色模式' : '切换为深色模式'}
        >
          {theme === 'dark' ? '☀️' : '🌙'}
        </button>
        <ul className="nav-links">
          {navItems.map(({ path, label }) => (
            <li key={path}>
              <NavLink to={path} className={({ isActive }) => isActive ? 'active' : ''}>
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
        {user && (
          <div className="nav-account">
            <Link to="/settings" className="nav-account-link" title="大模型设置">模型</Link>
            <Link to="/account" className="nav-account-link" title="账号设置">
              {user.displayName || user.username}
            </Link>
            <button className="nav-logout" onClick={handleLogout}>退出</button>
          </div>
        )}
      </div>
    </nav>
  )
}
