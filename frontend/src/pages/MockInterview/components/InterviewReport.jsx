export default function InterviewReport({ session }) {
  let reportData = null
  if (session.report && session.report.reportJson) {
    try {
      reportData = JSON.parse(session.report.reportJson)
    } catch (e) { }
  }

  if (!reportData) {
    return <div className="report-container"><h2>生成报告中或无数据...</h2></div>
  }

  return (
    <div className="report-container">
      <h2>面试评估报告</h2>
      <div style={{ display: 'flex', gap: '2rem', alignItems: 'center' }}>
        <div>
          <h3>总分: <span style={{ color: 'var(--primary-color)', fontSize: '2rem' }}>{reportData.overallScore}</span></h3>
          <p>评级: <strong>{reportData.overallLevel}</strong></p>
        </div>
        <p style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.05)', padding: '1rem', borderRadius: '8px' }}>
          {reportData.overallComment}
        </p>
      </div>

      <h3>多维度得分</h3>
      <div className="report-dimensions">
        {reportData.dimensions?.map((d, i) => (
          <div key={i} className="dimension-card">
            <h4>{d.name} <span style={{ color: 'var(--primary-color)' }}>{d.score}分</span></h4>
            <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)' }}>{d.comment}</p>
          </div>
        ))}
      </div>

      <h3>针对性改进建议</h3>
      <ul style={{ lineHeight: '1.8' }}>
        {reportData.improvementPlan?.map((plan, i) => (
          <li key={i}>{plan}</li>
        ))}
      </ul>
    </div>
  )
}
