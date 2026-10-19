import type { ActivityLevel, Goal, Sex, UserProfile, UserProfileInput } from './types';
export type QuestionnaireDraft = {
  age: string;
  sex: Sex | '';
  heightCm: string;
  weightKg: string;
  activityLevel: ActivityLevel | null;
  goal: Goal | '';
};
export const questionnaireFrom = (p: UserProfile): QuestionnaireDraft => ({
  age: p.age == null ? '' : String(p.age),
  sex: p.sex || '',
  heightCm: p.heightCm == null ? '' : String(p.heightCm),
  weightKg: p.weightKg == null ? '' : String(p.weightKg),
  activityLevel: p.activityLevel,
  goal: p.goal || '',
});
export function questionnaireErrors(d: QuestionnaireDraft, step?: number): Record<string, string> {
  const e: Record<string, string> = {};
  if (step === undefined || step === 0) {
    if (!/^\d+$/.test(d.age) || Number(d.age) < 18 || Number(d.age) > 32767)
      e.age = 'Укажите целый возраст от 18 лет.';
    if (!['MALE', 'FEMALE'].includes(d.sex)) e.sex = 'Выберите пол для расчёта.';
    if (!/^\d+$/.test(d.heightCm) || Number(d.heightCm) <= 0 || Number(d.heightCm) > 2147483647)
      e.heightCm = 'Укажите рост в сантиметрах целым числом больше 0.';
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(d.weightKg) || Number(d.weightKg) <= 0)
      e.weightKg = 'Укажите положительный вес до 999,99 кг, не более 2 знаков после запятой.';
  }
  if (
    (step === undefined || step === 1) &&
    !['SEDENTARY', 'LOW', 'MODERATE', 'HIGH', 'VERY_HIGH'].includes(d.activityLevel || '')
  )
    e.activityLevel = 'Выберите повседневную активность.';
  if (
    (step === undefined || step === 2) &&
    !['LOSE_WEIGHT', 'MAINTAIN_WEIGHT', 'GAIN_WEIGHT'].includes(d.goal)
  )
    e.goal = 'Выберите цель.';
  return e;
}
export function questionnairePayload(d: QuestionnaireDraft): UserProfileInput {
  if (Object.keys(questionnaireErrors(d)).length) throw new Error('Анкета не заполнена');
  return {
    age: Number(d.age),
    sex: d.sex as Sex,
    heightCm: Number(d.heightCm),
    weightKg: Number(d.weightKg),
    activityLevel: d.activityLevel!,
    goal: d.goal as Goal,
  };
}
export function hasQuestionnaire(p: UserProfile) {
  return !Object.keys(questionnaireErrors(questionnaireFrom(p))).length;
}
export const goalLabels: Record<Goal, string> = {
  LOSE_WEIGHT: 'Снижение веса',
  MAINTAIN_WEIGHT: 'Поддержание веса',
  GAIN_WEIGHT: 'Набор веса',
};
