import React from 'react';
import { Paper } from '@mui/material';
import { injectIntl } from 'react-intl';
import {
  ScatterChart, Scatter, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { getActivityScore } from '../services/UserService';

const QuizzesChart = (props) => {
  const {
    allUsers,
    totalOfPolls,
    quizzes,
    intl,
  } = props;

  if (!Object.keys(quizzes).length) return null;

  const chartData = [];

  Object.values(allUsers).forEach((u) => {
    if (u?.isModerator && Object.keys(u?.answers)?.length === 0) return;

    const result = Object
      .entries(quizzes || {})
      .map(([pollId, poll]) => {
        const userAnswers = u.answers[pollId] ?? [];
        const isCorrect = userAnswers.includes(poll.correctOption);
        return [pollId, isCorrect];
      });

    const activityScore = ((getActivityScore(u, allUsers, totalOfPolls) / 10) * 100).toFixed(2);
    const numberOfCorrectAnswers = result.filter(([, isCorrect]) => isCorrect).length;
    const numberOfQuizzes = Object.values(quizzes).length;
    const quizPerformance = ((numberOfCorrectAnswers / numberOfQuizzes) * 100).toFixed(2);

    const existingDot = chartData.find((v) => (v.x === activityScore && v.y === quizPerformance));

    if (existingDot) {
      existingDot.ids.push(u.userKey);
      existingDot.names.push(u.name);
      return;
    }

    chartData.push({
      ids: [u.userKey],
      names: [u.name],
      x: activityScore,
      y: quizPerformance,
    });
  });

  return (
    <Paper className="p-4">
      <h2 className="font-lg font-bold">
        {intl.formatMessage({
          id: 'app.learningDashboard.quizzes.chartTitle',
          defaultMessage: 'Quiz Performance vs Activity Level',
        })}
      </h2>
      <ResponsiveContainer width="100%" height={400}>
        <ScatterChart
          margin={{
            top: 20,
            right: 20,
            bottom: 20,
            left: 20,
          }}
        >
          <CartesianGrid />
          <XAxis
            type="number"
            dataKey="x"
            name={intl.formatMessage({
              id: 'app.learningDashboard.quizzes.activityLevel',
              defaultMessage: 'Activity Level',
            })}
            label={{
              value: `${intl.formatMessage({
                id: 'app.learningDashboard.quizzes.activityLevel',
                defaultMessage: 'Activity Level',
              })} (%)`,
              position: 'bottom',
            }}
          />
          <YAxis
            type="number"
            dataKey="y"
            name={intl.formatMessage({
              id: 'app.learningDashboard.quizzes.quizScore',
              defaultMessage: 'Quiz Score',
            })}
            label={{
              value: `${intl.formatMessage({
                id: 'app.learningDashboard.quizzes.quizScore',
                defaultMessage: 'Quiz Score',
              })} (%)`,
              position: 'insideLeft',
              angle: -90,
            }}
          />
          <Tooltip
            cursor={{ strokeDasharray: '3 3' }}
            content={(tooltipProps) => {
              const { active, payload } = tooltipProps;
              const isVisible = active && payload?.length;
              return (
                <Paper style={{ visibility: isVisible ? 'visible' : 'hidden' }} className="p-2">
                  {isVisible && (
                    <>
                      <p className="font-bold">
                        {[payload[0].payload.names.map((name) => (
                          <div key={name}>
                            {name}
                          </div>
                        ))]}
                      </p>
                      <p className="text-gray-600">
                        {`${intl.formatMessage({
                          id: 'app.learningDashboard.quizzes.activityLevel',
                          defaultMessage: 'Activity Level',
                        })}: ${payload[0].value}%`}
                      </p>
                      <p className="text-gray-600">
                        {`${intl.formatMessage({
                          id: 'app.learningDashboard.quizzes.quizScore',
                          defaultMessage: 'Quiz Score',
                        })}: ${payload[1].value}%`}
                      </p>
                    </>
                  )}
                </Paper>
              );
            }}
          />
          <Scatter
            name={intl.formatMessage({
              id: 'app.learningDashboard.quizzes.chartTitle',
              defaultMessage: 'Quiz Performance vs Activity Level',
            })}
            data={chartData}
            fill="#f97316"
          />
        </ScatterChart>
      </ResponsiveContainer>
    </Paper>
  );
};

export default injectIntl(QuizzesChart);
