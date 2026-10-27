import type { Alternative, ReplacementGoal } from './period-planning';
export const replacementLabels: Record<ReplacementGoal, string> = {
  SAME_PRODUCTS: 'Из тех же продуктов',
  CHEAPER: 'Дешевле',
  FASTER: 'Быстрее',
};
export function replacementChoices(items: Alternative[], goal: ReplacementGoal): Alternative[] {
  return items
    .filter((a) => a.goals?.includes(goal))
    .sort((a, b) => {
      const cost = (a.newPurchaseCost ?? Infinity) - (b.newPurchaseCost ?? Infinity);
      const added = (a.impact?.addedProducts.length ?? 0) - (b.impact?.addedProducts.length ?? 0);
      const time = a.preparationTimeMin - b.preparationTimeMin;
      const order =
        goal === 'FASTER'
          ? time || added || cost
          : goal === 'CHEAPER'
            ? cost || added || time
            : added || cost || time;
      return (
        order ||
        a.recipeId.localeCompare(b.recipeId) ||
        (a.candidateKey || '').localeCompare(b.candidateKey || '') ||
        a.portions - b.portions
      );
    })
    .slice(0, 3);
}
