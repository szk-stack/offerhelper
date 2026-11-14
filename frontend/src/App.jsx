import { Routes, Route } from 'react-router-dom'
import Layout from './components/Layout'
import RequireAuth from './components/RequireAuth'
import Home from './pages/Home'
import InterviewSearch from './pages/InterviewSearch'
import AIInterview from './pages/AIInterview'
import Replay from './pages/Replay'
import SmartAssistant from './pages/SmartAssistant'
import Resumes from './pages/Resumes'
import MockInterview from './pages/MockInterview/MockInterview'
import Login from './pages/Login'
import Account from './pages/Account'

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={(
          <RequireAuth>
            <Layout />
          </RequireAuth>
        )}
      >
        <Route index element={<Home />} />
        <Route path="resumes" element={<Resumes />} />
        <Route path="interviews" element={<InterviewSearch />} />
        <Route path="ai-interview" element={<AIInterview />} />
        <Route path="mock-interview/*" element={<MockInterview />} />
        <Route path="replay" element={<Replay />} />
        <Route path="assistant" element={<SmartAssistant />} />
        <Route path="account" element={<Account />} />
      </Route>
    </Routes>
  )
}

export default App
