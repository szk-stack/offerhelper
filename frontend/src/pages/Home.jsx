import { Link } from 'react-router-dom'
import { asset } from '../utils/asset'
import './Home.css'

const features = [
  { path: '/interviews', title: '面经搜索', desc: '按公司、部门检索整合的面经，支持多平台数据统一查看' },
  { path: '/mock-interview', title: '结构化面试', desc: '练习模式逐题点评纠错，真实模拟模式全程不提示、结束后统一给答案' },
  { path: '/replay', title: '面试复盘', desc: '上传真实面经，AI 进行深度复盘分析，给出改进建议' },
]

export default function Home() {
  return (
    <div className="home">
      <header className="hero">
        <img src={asset('logo.png')} alt="sspOffer" className="hero-logo" />
        <h1>sspOffer面经助手</h1>
        <p className="hero-sub">互联网后端面试准备平台 · 面经整合 · 结构化面试 · 深度复盘</p>
      </header>
      <section className="features">
        {features.map((f) => (
          <Link key={f.path} to={f.path} className="feature-card">
            <h3>{f.title}</h3>
            <p>{f.desc}</p>
            <span className="arrow">→</span>
          </Link>
        ))}
      </section>
    </div>
  )
}
