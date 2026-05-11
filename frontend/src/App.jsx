import { Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import Home from './pages/Home'
import InterviewSearch from './pages/InterviewSearch'
import AIInterview from './pages/AIInterview'
import Replay from './pages/Replay'
import SmartAssistant from './pages/SmartAssistant'
import Resumes from './pages/Resumes'
import MockInterview from './pages/MockInterview/MockInterview'

function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="resumes" element={<Resumes />} />
        <Route path="interviews" element={<InterviewSearch />} />
        <Route path="ai-interview" element={<AIInterview />} />
        <Route path="mock-interview/*" element={<MockInterview />} />
        <Route path="replay" element={<Replay />} />
        <Route path="assistant" element={<SmartAssistant />} />
      </Route>
    </Routes>
  )
}

export default App

