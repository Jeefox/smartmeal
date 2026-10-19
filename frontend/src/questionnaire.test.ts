import { describe, expect, it } from 'vitest';
import {
  hasQuestionnaire,
  questionnaireErrors,
  questionnaireFrom,
  questionnairePayload,
} from './questionnaire';
import type { QuestionnaireDraft } from './questionnaire';
import type { UserProfile } from './types';
const empty: UserProfile = {
  userId: 'user',
  dailyCalorieTarget: null,
  age: null,
  sex: null,
  heightCm: null,
  weightKg: null,
  activityLevel: null,
  goal: null,
};
const valid: QuestionnaireDraft = {
  age: '30',
  sex: 'MALE',
  heightCm: '180',
  weightKg: '80.5',
  activityLevel: 'MODERATE',
  goal: 'MAINTAIN_WEIGHT',
};
describe('Опрос', () => {
  it('сохраняет null незаполненного профиля пустыми полями, без выдуманных значений', () => {
    expect(questionnaireFrom(empty)).toEqual({
      age: '',
      sex: '',
      heightCm: '',
      weightKg: '',
      activityLevel: null,
      goal: '',
    });
    expect(hasQuestionnaire(empty)).toBe(false);
  });
  it('проверяет только поля текущего шага', () => {
    const draft = { ...valid, activityLevel: null, goal: '' as const };
    expect(questionnaireErrors(draft, 0)).toEqual({});
    expect(questionnaireErrors(draft, 1)).toHaveProperty('activityLevel');
    expect(questionnaireErrors(draft, 2)).toHaveProperty('goal');
  });
  it.each(['17', '18.5', '-1', ''])('не принимает возраст %s', (age) => {
    expect(questionnaireErrors({ ...valid, age }, 0)).toHaveProperty('age');
  });
  it.each(['0', '-1', '1000', '80.555'])('не принимает вес %s', (weightKg) => {
    expect(questionnaireErrors({ ...valid, weightKg }, 0)).toHaveProperty('weightKg');
  });
  it('передаёт только поля анкеты, без калорий и коэффициентов', () => {
    expect(questionnairePayload(valid)).toEqual({
      age: 30,
      sex: 'MALE',
      heightCm: 180,
      weightKg: 80.5,
      activityLevel: 'MODERATE',
      goal: 'MAINTAIN_WEIGHT',
    });
  });
  it('не изменяет ранее подтверждённую цель', () => {
    const p = { ...empty, dailyCalorieTarget: 2200 };
    questionnairePayload({ ...questionnaireFrom(p), ...valid });
    expect(p.dailyCalorieTarget).toBe(2200);
  });
  it('не формирует запрос из неполной анкеты', () => {
    expect(() => questionnairePayload(questionnaireFrom(empty))).toThrow();
  });
});
