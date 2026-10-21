# Фотографии подачи рецептов

Локальные WebP-файлы 480/960 px подготовлены из свободно лицензированных фотографий
Wikimedia Commons 21 октября 2026 года. Изображения уменьшены и обрезаны до 4:3 без
изменения содержания. Они показывают близкий вариант подачи для группы рецептов, поэтому
детали блюда могут отличаться от конкретного состава SmartMeal.

| Ключ           | Исходная фотография                                                                                                                          | Автор                 | Лицензия                                                        |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- | --------------------------------------------------------------- |
| `omelette`     | [An omelette](https://commons.wikimedia.org/wiki/File:An_omelette.jpg)                                                                       | Tom K / JacquesDemien | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)   |
| `porridge`     | [Apple oatmeal](https://commons.wikimedia.org/wiki/File:Apple_oatmeal.jpg)                                                                   | Shisma                | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)       |
| `pasta`        | [Pasta with tuna and tomatoes](<https://commons.wikimedia.org/wiki/File:Pasta_with_tuna_and_tomatoes_(21166418558).jpg>)                     | Luca Nebuloni         | [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/)       |
| `soup`         | [Chicken noodle soup](https://commons.wikimedia.org/wiki/File:Chicken_noodle_soup_with_a_little_carrot_and_black_pepper_-_Massachusetts.jpg) | Daderot               | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)   |
| `chicken-rice` | [Home-cooked Hainanese chicken rice](https://commons.wikimedia.org/wiki/File:Home-cooked_Hainanese_chicken_rice.jpg)                         | David / Kcdtsg        | Public domain                                                   |
| `salad`        | [Tomato and cucumber salad](https://commons.wikimedia.org/wiki/File:Македонска_салата_од_домат_и_краставица.jpg)                             | Делфина               | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)   |
| `fish`         | [Fried plaice with potatoes](https://commons.wikimedia.org/wiki/File:Scholle_Salzkartoffeln_Kellerhaus_Wei%C3%9Fenstadt.JPG)                 | Benreis               | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)       |
| `buckwheat`    | [Pilaf with buckwheat](https://commons.wikimedia.org/wiki/File:Pilaf_with_buckwheat_—_Гречка_менен_палоо.jpg)                                | Baydastann            | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)   |
| `beans`        | [Black bean stew](https://commons.wikimedia.org/wiki/File:Blackbeanstew.jpg)                                                                 | Badagnani             | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)       |
| `curd`         | [A bowl of cottage cheese](https://commons.wikimedia.org/wiki/File:Cottagecheese200px.jpg)                                                   | Fastily               | [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/) |

Привязка групп к UUID находится в `src/recipe-covers.ts`. Фото не загружаются с внешних
серверов во время просмотра, поэтому карточки не зависят от доступности Wikimedia Commons.
У рецептов без подходящей фотографии остаётся нейтральная заглушка.
