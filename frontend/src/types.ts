export type Nutrition = { calories: number; protein: number; fat: number; carbs: number };
export type Ingredient = {
  id?: string;
  productId: number;
  productName: string;
  shoppingName?: string;
  edibleYield?: number;
  quantityG: number;
  position?: number;
};
export type RecipeSummary = {
  id: string;
  name: string;
  description: string | null;
  preparationTimeMin: number;
  servings: number;
  nutritionPerServing?: Nutrition | null;
  image?: string;
  category?: string;
  equipment?: string[];
  allergens?: string[];
  mine?: boolean;
};
export type Recipe = RecipeSummary & {
  ingredients: Ingredient[];
  instructions: string;
  nutritionTotal?: Nutrition | null;
  createdAt?: string;
  updatedAt?: string;
};
export type RecipeInput = {
  name: string;
  description: string;
  instructions: string;
  preparationTimeMin: number;
  servings: number;
  ingredients: { productId: number; quantityG: number }[];
};
export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type Product = {
  shoppingName?: string;
  edibleYield?: number;
  id: number;
  categoryId: number;
  name: string;
  description?: string;
  caloriesPer100g: number;
  proteinPer100g: number;
  fatPer100g: number;
  carbsPer100g: number;
};
export type ProductCategory = { id: number; code: string; name: string };
export type Mode = 'demo' | 'api';
export type MealType = 'Завтрак' | 'Обед' | 'Ужин' | 'Перекус';
export type PlanEntry = {
  id: string;
  date: string;
  meal: MealType;
  recipe: Recipe;
  portions: number;
  locked: boolean;
};
export type ActivityLevel = 'SEDENTARY' | 'LOW' | 'MODERATE' | 'HIGH' | 'VERY_HIGH';
export type Profile = {
  activityLevel?: ActivityLevel | null;
  calories: number;
  protein: number;
  fat: number;
  carbs: number;
  timezone: string;
  allergies: string[];
  excluded: string;
  equipment: string[];
  city: string;
  store: string;
  budget: number;
  period: 'day' | 'week';
  loyalty: boolean;
};
export type ShoppingStatus = 'PLANNED' | 'PURCHASED' | 'NOT_FOUND';
export type PriceQuality = 'OBSERVED' | 'ESTIMATE' | 'STALE' | 'UNKNOWN';
export type Offer = {
  productId: number;
  name: string;
  packageG: number;
  priceCents: number | null;
  lowerCents?: number;
  upperCents?: number;
  quality: PriceQuality;
  observedAt: string;
  assumption?: string;
  source: string;
};
export type ShoppingItem = {
  productId: number;
  productName: string;
  category: string;
  requiredG: number;
  packs: number | null;
  purchasedG: number | null;
  remainderG: number | null;
  costCents: number | null;
  consumedCents: number | null;
  lowerCents: number | null;
  upperCents: number | null;
  offer: Offer | null;
  status: ShoppingStatus;
};
export type ShoppingList = {
  items: ShoppingItem[];
  createdAt: string;
  store: string;
  period: string;
  stale: boolean;
  budget: number;
  priceMode: 'known' | 'estimate';
};

export type Sex = 'MALE' | 'FEMALE';
export type Goal = 'LOSE_WEIGHT' | 'MAINTAIN_WEIGHT' | 'GAIN_WEIGHT';
export type UserProfile = {
  userId: string;
  dailyCalorieTarget: number | null;
  age: number | null;
  sex: Sex | null;
  heightCm: number | null;
  weightKg: number | null;
  activityLevel: ActivityLevel | null;
  goal: Goal | null;
};
export type UserProfileInput = {
  age: number;
  sex: Sex;
  heightCm: number;
  weightKg: number;
  activityLevel: ActivityLevel;
  goal: Goal;
};
export type EnergyEstimate = {
  restingCalories: number;
  maintenanceCalories: number;
  recommendedCalories: number;
};
