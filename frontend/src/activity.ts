import type { ActivityLevel } from './types';

// Questionnaire values agreed with the backend owner.
// All coefficients and nutrition estimates belong to the server.
export const activityOptions: {
  value: ActivityLevel;
  label: string;
  description: string;
}[] = [
  {
    value: 'SEDENTARY',
    label: 'Малоподвижный образ жизни',
    description: 'Большую часть дня сидя, мало движения',
  },
  {
    value: 'LOW',
    label: 'Низкая активность',
    description: 'Преимущественно сидячий день, регулярная ходьба',
  },
  {
    value: 'MODERATE',
    label: 'Умеренная активность',
    description: 'Много движения в течение дня, регулярные нагрузки',
  },
  {
    value: 'HIGH',
    label: 'Высокая активность',
    description: 'Физически активная работа или значительные ежедневные нагрузки',
  },
  {
    value: 'VERY_HIGH',
    label: 'Очень высокая активность',
    description: 'Тяжёлая физическая работа вместе с дополнительными нагрузками',
  },
];
