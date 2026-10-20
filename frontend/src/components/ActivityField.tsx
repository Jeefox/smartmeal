import { useId } from 'react';
import { activityOptions } from '../activity';
import type { ActivityLevel } from '../types';

export default function ActivityField({
  value,
  onChange,
  required = false,
}: {
  required?: boolean;
  value: ActivityLevel | null;
  onChange: (value: ActivityLevel | null) => void;
}) {
  const id = useId();
  return (
    <fieldset className="activity-field" aria-describedby={`${id}-hint`}>
      <legend>Повседневная активность</legend>
      <p className="muted" id={`${id}-hint`}>
        {required
          ? 'Выберите описание, которое ближе к вашему обычному дню: учитывайте работу, ходьбу и нагрузки. Выбор сам по себе не меняет суточную цель.'
          : 'Выберите описание, которое ближе к вашему обычному дню. При ручной цели поле можно оставить незаполненным. Выбор не меняет заданную калорийность.'}
      </p>
      <div className="activity-options">
        {activityOptions.map((option) => (
          <label
            key={option.value}
            className={`activity-option ${value === option.value ? 'selected' : ''}`}
          >
            <input
              type="radio"
              required={required}
              name={`${id}-activity`}
              value={option.value}
              checked={value === option.value}
              aria-describedby={`${id}-${option.value}`}
              onChange={() => onChange(option.value)}
            />
            <span>
              <b>{option.label}</b>
              <span id={`${id}-${option.value}`}>{option.description}</span>
            </span>
          </label>
        ))}
      </div>
      {!required && value !== null && (
        <button className="text-button" type="button" onClick={() => onChange(null)}>
          Не указывать активность
        </button>
      )}
    </fieldset>
  );
}
