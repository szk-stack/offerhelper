export default function InterviewReport({ session, onRegenerate, regenerating }) {
  const report = session?.report
  let reportData = null
  if (report && report.reportJson) {
    try {
      reportData = JSON.parse(report.reportJson)
    } catch (e) { }
  }

  const status = report?.status || (reportData ? 'SUCCESS' : null)
  const canRegenerate = typeof onRegenerate === 'function'

  if (status === 'GENERATING' || (!report && status !== 'EMPTY')) {
    return (
      <div className="report-container">
        <h2>面试评估报告</h2>
        <p className="report-hint">正在根据对话记录生成报告，通常需要 20-60 秒，页面会自动刷新…</p>
      </div>
    )
  }

  if (status === 'EMPTY') {
    return (
      <div className="report-container">
        <h2>面试评估报告</h2>
        <p className="report-hint">这场面试没有对话记录，暂时无法评分。</p>
      </div>
    )
  }

  if (status === 'FAILED') {
    return (
      <div className="report-container">
        <h2>面试评估报告</h2>
        <div className="report-banner error">
          <strong>报告生成失败</strong>
          <p>{report?.errorMessage || '模型返回的内容无法解析，可以重新生成一次。'}</p>
          <p className="report-banner-hint">对话记录已经完整保存，重新生成不会重复面试。</p>
          {canRegenerate && (
            <button className="btn primary" onClick={onRegenerate} disabled={regenerating}>
              {regenerating ? '正在重新生成...' : '重新生成报告'}
            </button>
          )}
        </div>
        {reportData?.rawText && (
          <details className="report-raw">
            <summary>查看原始返回内容（排查用）</summary>
            <pre>{reportData.rawText}</pre>
          </details>
        )}
      </div>
    )
  }

  if (!reportData) {
    return (
      <div className="report-container">
        <h2>面试评估报告</h2>
        <p className="report-hint">还没有可展示的报告内容。</p>
      </div>
    )
  }

  return (
    <div className="report-container">
      <h2>面试评估报告</h2>

      <div className="report-summary">
        <div className="report-score-block">
          <span className="report-score">{reportData.overallScore}</span>
          <span className="report-level">评级 {reportData.overallLevel}</span>
        </div>
        <p className="report-comment">{reportData.overallComment}</p>
      </div>

      {reportData.dimensions?.length > 0 && (
        <>
          <h3>多维度得分</h3>
          <div className="report-dimensions">
            {reportData.dimensions.map((d, i) => (
              <div key={i} className="dimension-card">
                <h4>{d.name} <span className="dimension-score">{d.score}分</span></h4>
                <p className="dimension-comment">{d.comment}</p>
              </div>
            ))}
          </div>
        </>
      )}

      {reportData.questionDetails?.length > 0 && (
        <>
          <h3>逐题点评</h3>
          <div className="question-details">
            {reportData.questionDetails.map((q, i) => (
              <div key={i} className="question-detail">
                <div className="question-detail-head">
                  <span className="question-index">Q{i + 1}</span>
                  <strong>{q.question}</strong>
                  {q.score !== undefined && q.score !== null && (
                    <span className="question-score">{q.score}/10</span>
                  )}
                </div>
                {q.candidateAnswer && (
                  <p className="question-answer">我的回答：{q.candidateAnswer}</p>
                )}
                {q.feedback && <p className="question-feedback">{q.feedback}</p>}
                {q.suggestion && <p className="question-suggestion">建议：{q.suggestion}</p>}
              </div>
            ))}
          </div>
        </>
      )}

      {reportData.improvementPlan?.length > 0 && (
        <>
          <h3>针对性改进建议</h3>
          <ul className="improvement-list">
            {reportData.improvementPlan.map((plan, i) => (
              <li key={i}>{plan}</li>
            ))}
          </ul>
        </>
      )}

      {canRegenerate && (
        <div className="report-footer">
          <button className="btn secondary" onClick={onRegenerate} disabled={regenerating}>
            {regenerating ? '正在重新生成...' : '重新生成报告'}
          </button>
        </div>
      )}
    </div>
  )
}
