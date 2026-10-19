'use strict';
const $ = (selector) => document.querySelector(selector);
const escapeHtml = (value = '') => String(value).replace(/[&<>"']/g, (char) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const photo = (id) => `https://images.unsplash.com/${id}?auto=format&fit=crop&w=900&q=80`;
const demoRecipes = [
  {id:'demo-1',name:'Боул с киноа и овощами',description:'Свежие овощи, нежное авокадо и лёгкая заправка',category:'Салаты',preparationTimeMin:25,servings:2,image:photo('photo-1512621776951-a57141f2eefd'),ingredients:[{productName:'Киноа',quantityG:150},{productName:'Авокадо',quantityG:150},{productName:'Томаты',quantityG:200},{productName:'Огурцы',quantityG:150}],instructions:'Промойте киноа и отварите согласно инструкции на упаковке. Дайте остыть.\nНарежьте огурцы, томаты и авокадо.\nРазложите киноа и овощи по мискам. Добавьте любимую заправку и подавайте.',nutritionPerServing:{calories:380,protein:12,fat:18,carbs:43}},
  {id:'demo-2',name:'Паста с томатами и базиликом',description:'Маленькое путешествие в Италию за полчаса',category:'Основные блюда',preparationTimeMin:30,servings:2,image:photo('photo-1473093295043-cdd812d0e601'),ingredients:[{productName:'Паста',quantityG:200},{productName:'Томаты черри',quantityG:250},{productName:'Базилик',quantityG:15},{productName:'Оливковое масло',quantityG:20}],instructions:'Отварите пасту до состояния аль денте, сохранив немного воды от варки.\nОбжарьте разрезанные томаты в оливковом масле до мягкости.\nПеремешайте с пастой, добавьте базилик и немного воды от варки.'},
  {id:'demo-3',name:'Тост с авокадо и яйцом',description:'Тот самый завтрак, ради которого хочется встать',category:'Завтраки',preparationTimeMin:15,servings:1,image:photo('photo-1525351484163-7529414344d8'),ingredients:[{productName:'Цельнозерновой хлеб',quantityG:70},{productName:'Авокадо',quantityG:100},{productName:'Яйцо',quantityG:60}],instructions:'Поджарьте хлеб на сухой сковороде или в тостере.\nРазомните авокадо вилкой и распределите по тостам.\nПриготовьте яйцо до желаемой степени готовности и выложите сверху.'},
  {id:'demo-4',name:'Тыквенный крем-суп',description:'Тёплый, бархатистый и очень домашний',category:'Супы',preparationTimeMin:40,servings:4,image:photo('photo-1476718406336-bb5a9690ee2a'),ingredients:[{productName:'Тыква',quantityG:700},{productName:'Морковь',quantityG:150},{productName:'Лук',quantityG:100},{productName:'Сливки',quantityG:100}],instructions:'Очистите тыкву, лук и морковь, нарежьте кубиками.\nОбжарьте лук, добавьте овощи и залейте водой, чтобы она их покрывала. Варите до мягкости.\nИзмельчите блендером, добавьте сливки и прогрейте.'},
  {id:'demo-5',name:'Панкейки с ягодами',description:'Пышный завтрак для неторопливого утра',category:'Завтраки',preparationTimeMin:20,servings:2,image:photo('photo-1528207776546-365bb710ee93'),ingredients:[{productName:'Молоко',quantityG:200},{productName:'Яйцо',quantityG:60},{productName:'Мука',quantityG:150},{productName:'Разрыхлитель',quantityG:5},{productName:'Ягоды',quantityG:100}],instructions:'Смешайте молоко и яйцо. Всыпьте муку с разрыхлителем и перемешайте до однородности.\nВыкладывайте тесто небольшими порциями на разогретую сковороду.\nПереворачивайте, когда на поверхности появятся пузырьки. Подавайте с ягодами.'},
  {id:'demo-6',name:'Шоколадный брауни',description:'Насыщенный шоколад. И ничего лишнего.',category:'Десерты',preparationTimeMin:45,servings:6,image:photo('photo-1606313564200-e75d5e30476c'),ingredients:[{productName:'Тёмный шоколад',quantityG:200},{productName:'Сливочное масло',quantityG:120},{productName:'Сахар',quantityG:150},{productName:'Яйцо',quantityG:180},{productName:'Мука',quantityG:80}],instructions:'Разогрейте духовку до 175 °C. Растопите шоколад с маслом и слегка остудите.\nПеремешайте яйца с сахаром, добавьте шоколад, затем муку.\nПерелейте тесто в форму с пергаментом. Выпекайте 25–30 минут. Полностью остудите перед нарезкой.'}
];
let storageAvailable = true;
function readStore(key, fallback) {
  try { const value = JSON.parse(localStorage.getItem(`smartmeal.${key}`)); return value ?? fallback; }
  catch { return fallback; }
}
function writeStore(key, value) {
  try { localStorage.setItem(`smartmeal.${key}`, JSON.stringify(value)); }
  catch { storageAvailable = false; toast('Браузер не разрешает сохранение. Данные доступны до перезагрузки.'); }
}
const modeValue = readStore('mode', 'demo');
let mode = modeValue === 'api' ? 'api' : 'demo';
let view = 'recipes';
let category = 'Все';
let productPage = 0;
let requestSequence = 0;
let loadedProducts = [];
const storedFavorites = readStore('favorites', []);
const favorites = new Set(Array.isArray(storedFavorites) ? storedFavorites : []);
const storedCustom = readStore('custom', []);
let customRecipes = Array.isArray(storedCustom) ? storedCustom.filter((item) => item && typeof item.name === 'string' && Array.isArray(item.ingredients)) : [];
const storedApi = readStore('apiRecipes', []);
let apiRecipes = Array.isArray(storedApi) ? storedApi.filter((item) => item && typeof item.name === 'string' && Array.isArray(item.ingredients)) : [];
let toastTimer;
function toast(message) { $('#toast').textContent = message; $('#toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 4000); }
function allRecipes() { return mode === 'demo' ? [...customRecipes,...demoRecipes] : apiRecipes; }
function formatNumber(number) { return new Intl.NumberFormat('ru-RU',{maximumFractionDigits:1}).format(Number(number) || 0); }
async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {headers:{'Accept':'application/json',...(options.body ? {'Content-Type':'application/json'} : {})}, ...options});
  const contentType = response.headers.get('content-type') || '';
  const data = contentType.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(data?.detail || data?.title || `Ошибка сервера: ${response.status}`);
  if (data === null) throw new Error('API недоступен на этом адресе. Откройте интерфейс через Spring Boot или включите деморежим.');
  return data;
}
function imageMarkup(recipe, detail = false) {
  return recipe.image ? `<img ${detail ? 'class="detail-image"' : ''} src="${escapeHtml(recipe.image)}" alt="${escapeHtml(recipe.name)}" ${detail ? '' : 'loading="lazy"'}>` : '<div class="placeholder-photo" aria-hidden="true">✳</div>';
}
function render() {
  const titles = {recipes:'Все рецепты',favorites:'Избранное',mine:'Мои рецепты',products:'Продукты'};
  $('#breadcrumb').textContent = titles[view];
  $('#section-title').innerHTML = `${titles[view]} <span id="visible-count"></span>`;
  $('#hero').hidden = view !== 'recipes';
  $('#categories').hidden = view === 'products';
  $('#time-filter').hidden = view === 'products';
  $('#sort').hidden = view === 'products';
  $('#search').placeholder = view === 'products' ? 'Найти продукт…' : 'Найти рецепт или ингредиент…';
  $('#search').setAttribute('aria-label',view === 'products' ? 'Поиск продуктов' : 'Поиск рецептов');
  $('#mode-note').textContent = mode === 'demo' ? 'Деморежим · данные в браузере' : 'Подключение к SmartMeal API';
  $('#connection-label').textContent = mode === 'demo' ? 'Деморежим' : 'SmartMeal API';
  $('#status-dot').style.background = mode === 'demo' ? '#daa35b' : '#7e9d61';
  document.querySelectorAll('[data-view]').forEach((item) => { item.classList.toggle('active',item.dataset.view === view); item.setAttribute('aria-current',item.dataset.view === view ? 'page' : 'false'); });
  $('#recipe-count').textContent = allRecipes().length;
  $('#favorite-count').textContent = allRecipes().filter((recipe) => favorites.has(recipe.id)).length;
  if (view === 'products') { renderProducts(); return; }
  requestSequence++;
  $('#notice').hidden = mode !== 'api';
  $('#notice').textContent = 'API пока не предоставляет список всех рецептов. Здесь — рецепты, созданные или открытые по ID в этом браузере. Открыть рецепт по ID можно в настройках подключения слева.';
  const query = $('#search').value.trim().toLocaleLowerCase('ru');
  const maxTime = Number($('#time-filter').value);
  let recipes = allRecipes().filter((recipe) => (view !== 'favorites' || favorites.has(recipe.id)) && (view !== 'mine' || recipe.mine) && (category === 'Все' || recipe.category === category) && (!maxTime || recipe.preparationTimeMin <= maxTime) && `${recipe.name} ${recipe.description || ''} ${recipe.ingredients.map((ingredient) => ingredient.productName).join(' ')}`.toLocaleLowerCase('ru').includes(query));
  if ($('#sort').value === 'time') recipes.sort((a,b) => a.preparationTimeMin - b.preparationTimeMin);
  if ($('#sort').value === 'name') recipes.sort((a,b) => a.name.localeCompare(b.name,'ru'));
  $('#visible-count').textContent = recipes.length;
  $('#result-summary').textContent = `Показано ${recipes.length} из ${allRecipes().length} рецептов`;
  $('#empty').hidden = recipes.length > 0;
  $('#empty-text').textContent = view === 'favorites' ? 'Нажмите на сердечко у понравившегося рецепта — он появится здесь.' : view === 'mine' ? 'Добавьте первый собственный рецепт с помощью кнопки сверху.' : 'Попробуйте другой запрос, снимите фильтры или добавьте рецепт.';
  $('#grid').innerHTML = recipes.map((recipe) => `<article class="recipe-card"><button class="favorite ${favorites.has(recipe.id) ? 'saved' : ''}" data-favorite="${escapeHtml(recipe.id)}" aria-label="${favorites.has(recipe.id) ? 'Убрать из избранного' : 'В избранное'}: ${escapeHtml(recipe.name)}" aria-pressed="${favorites.has(recipe.id)}">${favorites.has(recipe.id) ? '♥' : '♡'}</button><button class="card-open" data-open="${escapeHtml(recipe.id)}"><div class="card-image">${imageMarkup(recipe)}<span class="card-category">${escapeHtml(recipe.category || 'Мой рецепт')}</span></div><div class="card-content"><h3 class="card-title">${escapeHtml(recipe.name)}</h3><p class="card-description">${escapeHtml(recipe.description || 'Ваш новый любимый рецепт')}</p><div class="card-meta"><span>◷ ${recipe.preparationTimeMin} мин</span><span>♧ ${recipe.servings} порц.</span><span>${recipe.nutritionPerServing ? `${formatNumber(recipe.nutritionPerServing.calories)} ккал` : 'Смотреть →'}</span></div></div></button></article>`).join('');
}
function openRecipe(id) {
  const recipe = allRecipes().find((item) => item.id === id);
  if (!recipe) return;
  const nutrition = recipe.nutritionPerServing;
  $('#recipe-detail').innerHTML = `<div class="dialog-header"><div><div class="eyebrow green">${escapeHtml(recipe.category || 'ВАШ РЕЦЕПТ')}</div><h2>${escapeHtml(recipe.name)}</h2></div><button class="icon-button" data-close aria-label="Закрыть">×</button></div>${recipe.image ? imageMarkup(recipe,true) : ''}<p class="detail-description">${escapeHtml(recipe.description || '')}</p><div class="detail-meta"><span>◷ ${recipe.preparationTimeMin} минут</span><span>♧ ${recipe.servings} порц.</span><button class="text-button" data-detail-favorite="${escapeHtml(recipe.id)}">${favorites.has(recipe.id) ? '♥ В избранном' : '♡ Сохранить'}</button></div>${nutrition ? `<div class="nutrition">${[['calories','ккал'],['protein','белки, г'],['fat','жиры, г'],['carbs','углеводы, г']].map(([key,label]) => `<div><b>${formatNumber(nutrition[key])}</b>${label}</div>`).join('')}</div><p class="form-hint">Пищевая ценность на порцию${recipe.id.startsWith('demo-') ? ' · примерные демонстрационные значения' : ''}</p>` : ''}<h3>Ингредиенты</h3><label class="portion-control form-hint">Количество порций <input type="number" id="detail-servings" min="1" max="1000" value="${recipe.servings}" style="width:75px;padding:7px;border:1px solid #dce2d2;border-radius:5px"></label><ul class="detail-ingredients">${recipe.ingredients.map((ingredient) => `<li><span>${escapeHtml(ingredient.productName)}</span><b data-quantity="${Number(ingredient.quantityG)}">${formatNumber(ingredient.quantityG)} г</b></li>`).join('')}</ul><h3>Как приготовить</h3><ol class="steps">${recipe.instructions.split(/\n+/).filter(Boolean).map((step) => `<li>${escapeHtml(step)}</li>`).join('')}</ol><p class="detail-footer">${recipe.id.startsWith('demo-') ? 'Демонстрационный рецепт' : `ID: ${escapeHtml(recipe.id)}`}</p>`;
  $('#detail-servings').addEventListener('input',(event) => { const portions = Number(event.target.value); if (portions < 1 || portions > 1000) return; document.querySelectorAll('[data-quantity]').forEach((item) => { item.textContent = `${formatNumber(Number(item.dataset.quantity) * portions / recipe.servings)} г`; }); });
  $('#recipe-dialog').showModal();
}
function toggleFavorite(id) { favorites.has(id) ? favorites.delete(id) : favorites.add(id); writeStore('favorites',[...favorites]); render(); }
let demoProducts = [
  {id:1,name:'Томаты',caloriesPer100g:18,proteinPer100g:0.9,fatPer100g:0.2,carbsPer100g:3.9},
  {id:2,name:'Авокадо',caloriesPer100g:160,proteinPer100g:2,fatPer100g:14.7,carbsPer100g:8.5},
  {id:3,name:'Киноа',caloriesPer100g:368,proteinPer100g:14,fatPer100g:6,carbsPer100g:64},
  {id:4,name:'Яйцо',caloriesPer100g:143,proteinPer100g:12.6,fatPer100g:9.5,carbsPer100g:0.7},
  {id:5,name:'Творог',caloriesPer100g:121,proteinPer100g:17,fatPer100g:5,carbsPer100g:1.8},
  {id:6,name:'Огурцы',caloriesPer100g:15,proteinPer100g:0.7,fatPer100g:0.1,carbsPer100g:3.6}
];
async function renderProducts() {
  const sequence = ++requestSequence;
  $('#empty').hidden = true;
  $('#notice').hidden = false;
  $('#notice').textContent = mode === 'demo' ? 'Демонстрационный справочник. Пищевая ценность указана на 100 г продукта.' : 'Справочник SmartMeal. Пищевая ценность на 100 г. Продукты добавляются через API бэкенда.';
  $('#grid').innerHTML = '<p class="form-hint">Загружаем продукты…</p>';
  try {
    const query = $('#search').value.trim();
    const data = mode === 'demo' ? {content:demoProducts.filter((item) => item.name.toLowerCase().includes(query.toLowerCase())),totalPages:1,totalElements:demoProducts.length} : await api(`/products?q=${encodeURIComponent(query)}&page=${productPage}&size=12`);
    if (sequence !== requestSequence) return;
    $('#visible-count').textContent = data.totalElements;
    $('#result-summary').textContent = `Продуктов: ${data.totalElements}`;
    $('#grid').innerHTML = data.content.map((product) => `<article class="recipe-card product-card"><div class="eyebrow green">ПРОДУКТ · #${product.id}</div><h3>${escapeHtml(product.name)}</h3><p>${escapeHtml(product.description || 'Пищевая ценность на 100 г')}</p><strong>${formatNumber(product.caloriesPer100g)} ккал</strong><p>Белки ${formatNumber(product.proteinPer100g)} г<br>Жиры ${formatNumber(product.fatPer100g)} г<br>Углеводы ${formatNumber(product.carbsPer100g)} г</p></article>`).join('');
    $('#empty').hidden = data.content.length > 0;
    if (data.totalPages > 1) $('#grid').insertAdjacentHTML('beforeend',`<div class="product-pager"><button class="button" data-page="${productPage-1}" ${productPage === 0 ? 'disabled' : ''}>←</button><span>${productPage+1} / ${data.totalPages}</span><button class="button" data-page="${productPage+1}" ${productPage+1 >= data.totalPages ? 'disabled' : ''}>→</button></div>`);
  } catch (error) { if (sequence === requestSequence) { $('#grid').innerHTML = ''; $('#notice').textContent = error.message; $('#visible-count').textContent = '—'; $('#result-summary').textContent = 'Продукты не загружены'; } }
}
function addIngredient() {
  const row = document.createElement('div');
  row.className = 'ingredient-row';
  row.innerHTML = mode === 'demo' ? '<input class="ingredient-product" required placeholder="Название продукта" aria-label="Название продукта">' : `<select class="ingredient-product" required aria-label="Продукт"><option value="">Выберите продукт</option>${loadedProducts.map((product) => `<option value="${product.id}">${escapeHtml(product.name)}</option>`).join('')}</select>`;
  row.insertAdjacentHTML('beforeend','<input class="ingredient-grams" type="number" min="0.01" max="99999999.99" step="0.01" required placeholder="г" aria-label="Масса в граммах"><button type="button" class="remove-ingredient" aria-label="Удалить ингредиент">×</button>');
  $('#ingredients').append(row);
}
async function openCreate() {
  $('#recipe-form').reset(); $('#ingredients').innerHTML = ''; $('#form-error').textContent = '';
  $('#create-hint').textContent = mode === 'demo' ? 'Деморежим: рецепт сохранится в этом браузере.' : 'Рецепт будет сохранён в базе SmartMeal. Загружаем справочник продуктов…';
  $('#save-recipe').disabled = mode === 'api'; $('#add-ingredient').disabled = mode === 'api';
  $('#create-dialog').showModal();
  if (mode === 'api') {
    try {
      loadedProducts = [];
      let page = 0;
      let data;
      do { data = await api(`/products?page=${page}&size=100`); loadedProducts.push(...data.content); page++; } while (page < data.totalPages);
      $('#create-hint').textContent = loadedProducts.length ? 'Рецепт будет сохранён в базе SmartMeal. Выберите продукты из справочника.' : 'В справочнике пока нет продуктов. Сначала добавьте их через POST /api/products.';
      $('#save-recipe').disabled = !loadedProducts.length; $('#add-ingredient').disabled = !loadedProducts.length;
    } catch (error) { $('#form-error').textContent = error.message; return; }
  } else { $('#save-recipe').disabled = false; $('#add-ingredient').disabled = false; }
  addIngredient();
}
$('#recipe-form').addEventListener('submit',async (event) => {
  event.preventDefault(); $('#form-error').textContent = '';
  const formData = new FormData(event.target);
  const rows = [...document.querySelectorAll('.ingredient-row')];
  if (!rows.length) { $('#form-error').textContent = 'Добавьте хотя бы один ингредиент.'; return; }
  const ingredients = rows.map((row) => ({productId:Number(row.querySelector('.ingredient-product').value),productName:mode === 'demo' ? row.querySelector('.ingredient-product').value.trim() : row.querySelector('.ingredient-product').selectedOptions[0].textContent,quantityG:Number(row.querySelector('.ingredient-grams').value)}));
  const names = ingredients.map((item) => mode === 'api' ? item.productId : item.productName.toLowerCase());
  if (new Set(names).size !== names.length) { $('#form-error').textContent = 'Продукт повторяется. Объедините массу в одной строке.'; return; }
  const payload = {name:formData.get('name').trim(),description:formData.get('description').trim(),instructions:formData.get('instructions').trim(),preparationTimeMin:Number(formData.get('preparationTimeMin')),servings:Number(formData.get('servings')),ingredients:ingredients.map(({productId,quantityG}) => ({productId,quantityG}))};
  if (!payload.name || !payload.instructions || ingredients.some((item) => !item.productName)) { $('#form-error').textContent = 'Название, инструкция и продукты не могут состоять только из пробелов.'; return; }
  $('#save-recipe').disabled = true;
  try {
    let recipe;
    if (mode === 'api') { recipe = {...await api('/recipes',{method:'POST',body:JSON.stringify(payload)}),mine:true}; apiRecipes.unshift(recipe); writeStore('apiRecipes',apiRecipes); }
    else { recipe = {...payload,id:crypto.randomUUID(),ingredients,mine:true,category:'Мой рецепт'}; customRecipes.unshift(recipe); writeStore('custom',customRecipes); }
    $('#create-dialog').close(); setView('mine'); toast(storageAvailable ? 'Рецепт сохранён. Приятного аппетита!' : 'Рецепт создан на время текущей сессии.');
  } catch (error) { $('#form-error').textContent = error.message; }
  finally { $('#save-recipe').disabled = false; }
});
function setView(next) { view = next; category = 'Все'; productPage = 0; $('#search').value = ''; $('#time-filter').value = '0'; document.querySelectorAll('[data-category]').forEach((item) => { item.classList.toggle('active',item.dataset.category === 'Все'); item.setAttribute('aria-pressed',String(item.dataset.category === 'Все')); }); render(); }
document.addEventListener('click',(event) => {
  const button = event.target.closest('button'); if (!button) return;
  if (button.hasAttribute('data-close')) button.closest('dialog').close();
  if (button.dataset.view) setView(button.dataset.view);
  if (button.dataset.category) { category = button.dataset.category; document.querySelectorAll('[data-category]').forEach((item) => { item.classList.toggle('active',item === button); item.setAttribute('aria-pressed',String(item === button)); }); render(); }
  if (button.dataset.favorite) toggleFavorite(button.dataset.favorite);
  if (button.dataset.detailFavorite) { const id = button.dataset.detailFavorite; toggleFavorite(id); button.textContent = favorites.has(id) ? '♥ В избранном' : '♡ Сохранить'; }
  if (button.dataset.open) openRecipe(button.dataset.open);
  if (button.classList.contains('remove-ingredient')) button.closest('.ingredient-row').remove();
  if (button.dataset.page !== undefined) { productPage = Number(button.dataset.page); renderProducts(); }
});
$('#add-recipe').addEventListener('click',openCreate);
$('#add-ingredient').addEventListener('click',addIngredient);
$('#show-favorites').addEventListener('click',() => setView('favorites'));
$('#surprise').addEventListener('click',() => { const recipes = allRecipes(); if (recipes.length) openRecipe(recipes[Math.floor(Math.random()*recipes.length)].id); else toast('Добавьте первый рецепт, и мы поможем выбрать.'); });
let searchTimer;
$('#search').addEventListener('input',() => { productPage = 0; clearTimeout(searchTimer); searchTimer = setTimeout(render,180); });
$('#time-filter').addEventListener('change',render); $('#sort').addEventListener('change',render);
$('#reset').addEventListener('click',() => setView(view));
$('.brand').addEventListener('click',() => setView('recipes'));
$('#connection').addEventListener('click',() => { $('#data-mode').value = mode; $('#lookup-id').value = ''; $('#settings-error').textContent = ''; $('#settings-dialog').showModal(); });
$('#settings-form').addEventListener('submit',async (event) => {
  event.preventDefault(); $('#settings-error').textContent = '';
  const nextMode = $('#data-mode').value;
  const id = $('#lookup-id').value.trim();
  if (id && nextMode !== 'api') { $('#settings-error').textContent = 'Для открытия рецепта из базы выберите SmartMeal API.'; return; }
  const submit = event.target.querySelector('[type="submit"]'); submit.disabled = true;
  try {
    if (nextMode === 'api') {
      await api('/products?size=1');
      if (id) {
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) throw new Error('Введите корректный UUID рецепта.');
        const recipe = await api(`/recipes/${encodeURIComponent(id)}`);
        if (!apiRecipes.some((item) => item.id === recipe.id)) { apiRecipes.unshift(recipe); writeStore('apiRecipes',apiRecipes); }
      }
    }
    mode = nextMode; writeStore('mode',mode); $('#settings-dialog').close(); setView('recipes');
    if (id) openRecipe(id); else toast(mode === 'api' ? 'Подключение к API установлено' : 'Деморежим включён');
  } catch (error) { $('#settings-error').textContent = error.message; }
  finally { submit.disabled = false; }
});
document.querySelectorAll('dialog').forEach((dialog) => dialog.addEventListener('click',(event) => { if (event.target === dialog) { const rect = dialog.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close(); } }));
render();
