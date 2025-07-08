import React from 'react';
import { defineMessages, useIntl } from 'react-intl';
import Styled from '../styles';
import { pollTypes } from '../service';
import ResponseArea from './ResponseArea';

const intlMessages = defineMessages({
  typedResponseDesc: {
    id: 'app.poll.typedResponse.desc',
    description: '',
  },
  responseChoices: {
    id: 'app.poll.responseChoices.label',
    description: '',
  },
  pollingQuestion: {
    id: 'app.polling.pollQuestionTitle',
    description: 'polling question header',
  },
});

interface ResponseChoicesProps {
  type: string | null;
  toggleMultipleResponse: () => void;
  multipleResponse: boolean;
  optList: Array<{ key: string; val: string }>;
  handleAddOption: () => void;
  secretPoll: boolean;
  question: string | string[];
  setError: (err: string) => void;
  setIsPolling: (isPolling: boolean) => void;
  handleToggle: () => void;
  error: string | null;
  handleInputChange: (e: React.ChangeEvent<HTMLInputElement>, i: number) => void;
  handleRemoveOption: (i: number) => void;
  customInput: boolean;
  questionAndOptions: string[] | string;
  isQuiz: boolean;
  correctAnswer: {
    text: string;
    index: number;
  };
  setCorrectAnswer: (param: {text: string, index: number }) => void;
}

const ResponseChoices: React.FC<ResponseChoicesProps> = ({
  type,
  toggleMultipleResponse,
  multipleResponse,
  optList,
  handleAddOption,
  secretPoll,
  question,
  setError,
  setIsPolling,
  handleToggle,
  error,
  handleInputChange,
  handleRemoveOption,
  customInput,
  questionAndOptions,
  isQuiz,
  correctAnswer,
  setCorrectAnswer,
}) => {
  const intl = useIntl();
  if ((!customInput && type) || (questionAndOptions && customInput)) {
    return (
      <div data-test="responseChoices">
        {customInput && questionAndOptions && (
          <Styled.Question>
            <Styled.SectionHeading>
              {intl.formatMessage(intlMessages.pollingQuestion)}
            </Styled.SectionHeading>
            <Styled.PollParagraph>
              <span>{question}</span>
            </Styled.PollParagraph>
          </Styled.Question>
        )}
        <Styled.SectionHeading>
          {intl.formatMessage(intlMessages.responseChoices)}
        </Styled.SectionHeading>
        {type === pollTypes.Response && (
          <Styled.PollParagraph>
            <span>{intl.formatMessage(intlMessages.typedResponseDesc)}</span>
          </Styled.PollParagraph>
        )}
        <ResponseArea
          error={error}
          type={type}
          toggleMultipleResponse={toggleMultipleResponse}
          multipleResponse={multipleResponse}
          optList={optList}
          handleAddOption={handleAddOption}
          secretPoll={secretPoll}
          question={question}
          setError={setError}
          setIsPolling={setIsPolling}
          handleToggle={handleToggle}
          handleInputChange={handleInputChange}
          handleRemoveOption={handleRemoveOption}
          isQuiz={isQuiz}
          correctAnswer={correctAnswer}
          setCorrectAnswer={setCorrectAnswer}
        />
      </div>
    );
  }
  return null;
};

export default ResponseChoices;
