import React from 'react';
import { defineMessages, useIntl } from 'react-intl';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import Box from '@mui/material/Box';
import Styled from '../styles';

interface QuizAndPollTabSelectorProps {
  isQuiz: boolean;
  onTabChange: (isQuiz: boolean) => void;
}

const intlMessages = defineMessages({
  tabPollLabel: {
    id: 'app.learningDashboard.userDetails.poll',
    description: 'Tab label for Poll',
  },
  tabQuizLabel: {
    id: 'app.poll.quiz',
    description: 'Tab label for Quiz',
  },
  tabSelectorAriaLabel: {
    id: 'app.poll.tabSelector.ariaLabel',
    description: 'ARIA label for the Quiz and Poll tab selector',
  },
});

const QuizAndPollTabSelector: React.FC<QuizAndPollTabSelectorProps> = ({ isQuiz, onTabChange }) => {
  const intl = useIntl();
  return (
    <Styled.TabSelectorWrapper>
      <Box sx={{ width: '100%' }}>
        <Tabs
          value={isQuiz ? 'quiz' : 'poll'}
          onChange={(_, value) => {
            onTabChange(value === 'quiz');
          }}
          aria-label={intl.formatMessage(intlMessages.tabSelectorAriaLabel)}
          centered
        >
          <Tab value="poll" label={intl.formatMessage(intlMessages.tabPollLabel)} />
          <Tab value="quiz" label={intl.formatMessage(intlMessages.tabQuizLabel)} />
        </Tabs>
      </Box>
    </Styled.TabSelectorWrapper>
  );
};

export default QuizAndPollTabSelector;
