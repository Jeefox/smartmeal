package dev.jeefox.smartmeal.price.refresh;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Conservative ingredient matching: source discovery never treats the first search hit as a match. */
@Component
public class CatalogueProductMatcher {
    private static final String PREPARED = "суп|салат|пюре|котлет|готов|отварн|варен|жарен|запеч|копчен|маринов|соус|смузи|пюреш|десерт|печень|батончик|шоколад|мороженое|ассорти|смесь|гриль|вялен|сушен|деликатес|пряност|перец|чеснок|прованс|паниров|конфет|кондитер|вафл|кекс|джем|варенье|пастила|чипс|микрозелен|проростк";
    private static final Map<String, String> QUERIES = Map.ofEntries(
            Map.entry("овсяные хлопья","хлопья овсяные"),Map.entry("макароны сухие","макароны"),
            Map.entry("гречневая крупа","крупа гречневая"),Map.entry("картофель очищенный","картофель"),Map.entry("рис сухой","рис"),
            Map.entry("куриное филе сырое","филе куриное"),Map.entry("филе индейки сырое","филе индейки"),
            Map.entry("минтай филе сырое","минтай филе"),Map.entry("тунец консервированный в собственном соку","тунец в собственном соку"),
            Map.entry("яйцо куриное без скорлупы","яйцо куриное"),Map.entry("банан без кожуры","бананы"),
            Map.entry("яблоко без сердцевины","яблоки"),Map.entry("чечевица сухая","чечевица"),
            Map.entry("фасоль консервированная без заливки","фасоль в собственном соку"),
            Map.entry("грецкий орех очищенный","орех грецкий"),Map.entry("йогурт натуральный без сахара","йогурт греческий"),
            Map.entry("сыр полутвердый","сыр полутвердый"),Map.entry("лук репчатый","лук репчатый"));

    public String query(String ingredient) {
        String key = normalize(ingredient);
        return QUERIES.getOrDefault(key,ingredient);
    }

    public boolean matches(String ingredient, String title) {
        String key = normalize(ingredient), name = normalize(title);
        if (Pattern.compile(PREPARED).matcher(name).find()) return false;
        if (key.contains("греч")) return (name.contains("гречка") || name.contains("гречнев"))
                && !Pattern.compile("хлопь|каша|мука|быстр").matcher(name).find();
        if (key.contains("тунец")) return name.contains("тунец")
                && (name.contains("собственном соку") || name.contains("натурал"))
                && !Pattern.compile("масл|оливков|томат|чили").matcher(name).find();
        if (key.contains("фасоль")) return name.contains("фасоль")
                && !Pattern.compile("томат|соус|чили|кукуруз|овощ|печен|монастыр|буррит|барбекю|паприкаш|стручков")
                .matcher(name).find();
        if (key.contains("йогурт")) return name.contains("йогурт") && (name.contains("натурал") || name.contains("греческ"))
                && !Pattern.compile("клубник|ванил|сахар|фрукт|персик|манго|вишн|черешн|черник|малина|злак|мед").matcher(name).find();
        if (key.equals("яйцо куриное без скорлупы")) return name.contains("яйц") && name.contains("курин") && !name.contains("перепел");
        if (key.contains("грецкий")) return name.contains("грецк") && (name.contains("очищ") || name.contains("ядр"))
                && !Pattern.compile("скорлуп|глазур|карамел|сахар|солен").matcher(name).find();
        if (key.contains("молоко")) return name.contains("молоко") && matchesFat(key,name)
                && !Pattern.compile("козье|раститель|кокос|овсян|миндал|топлен|безлактоз|сгущен").matcher(name).find();
        if (key.contains("творог")) return name.contains("творог") && matchesFat(key,name)
                && !Pattern.compile("масс|изюм|черник|клубник|ванил|фрукт|персик|манго").matcher(name).find();
        if (key.equals("куриное филе сырое") || key.equals("филе индейки сырое") || key.equals("минтай филе сырое")) return name.contains("филе") && !name.contains("фарш")
                && (key.contains("курин") ? (name.contains("курин") || name.contains("цыплен")) && !name.contains("бедр") : key.contains("индей") ? name.contains("индей") : name.contains("минта"));
        if (key.contains("рис")) return Pattern.compile("(^| )рис( |,|$)").matcher(name).find() && !name.contains("мука") && !name.contains("каш");
        if (key.contains("банан")) return name.startsWith("банан") && !name.contains("сушен") && !name.contains("чипс");
        if (key.contains("яблок")) return name.startsWith("яблок") && !name.contains("сушен") && !name.contains("сок") && !name.contains("уксус");
        if (key.contains("брокколи")) return (name.startsWith("брокколи") || name.startsWith("капуста брокколи"))
                && !name.contains("цветн");
        if (key.contains("помидор")) return (name.contains("томат") || name.contains("помидор"))
                && !Pattern.compile("паст|сок|вялен|протерт|мякоть|пассата|резан|консерв").matcher(name).find();
        if (key.contains("огурец")) return name.contains("огур") && !name.contains("солен");
        if (key.contains("морков")) return name.startsWith("морковь")
                && !Pattern.compile("корейск|квашен|марин|заправ|сок|капуст").matcher(name).find();
        if (key.contains("чечевиц")) return name.contains("чечевиц") && !name.contains("мука") && !name.contains("каша");
        if (key.contains("масло подсолнечное")) return name.contains("масло") && name.contains("подсолнеч")
                && !Pattern.compile("смесь|добавлен|оливков").matcher(name).find();
        var tokens=Arrays.stream(query(key).split("[^а-я0-9,%.]+"))
                .filter(word -> word.length()>1).toList();
        return !tokens.isEmpty() && tokens.stream().allMatch(word -> {
            String stem=word.length()>5 ? word.substring(0,word.length()-2) : word;
            return Pattern.compile("(^|[^а-я0-9])"+Pattern.quote(stem)
                    +(word.length()>5 ? "[а-я]*" : "")+"([^а-я0-9]|$)").matcher(name).find();
        });
    }

    public boolean matchesCategory(String ingredient, String category) {
        String key=normalize(ingredient), name=normalize(category);
        boolean meatRoot = name.equals("мясо и птица") || name.equals("птица, мясо")
                || name.equals("мясо, птица, колбасы");
        if (key.equals("куриное филе сырое")) return meatRoot || name.startsWith("мясо, птица//курица");
        if (key.equals("филе индейки сырое")) return meatRoot || name.startsWith("мясо, птица//индейка");
        if (key.equals("минтай филе сырое")) return name.contains("рыба") && !Pattern.compile("деликатес|сушен|вялен|готов|снек").matcher(name).find();
        if (key.contains("тунец") || key.contains("фасоль")) return name.contains("консерв");
        if (Pattern.compile("(^|[^а-я])(?:банан|яблок|помидор|огур|морков|лук|картоф|брокколи)[а-я]*($|[^а-я])").matcher(key).find()) {
            return name.startsWith("овощи, фрукты") || name.startsWith("овощи и фрукты")
                    || name.startsWith("супермаркет//овощи, фрукты");
        }
        if (key.contains("йогурт")) return name.equals("молочный прилавок") || name.equals("молоко, сыр, яйца")
                || name.equals("молочные продукты, сыр, яйца")
                || name.contains("молочные продукты") && (name.contains("йогурт") || name.contains("кисломолоч"));
        if (key.contains("грецкий")) return (name.contains("орехи") || name.equals("снеки")) && !name.contains("кондитер");
        return !Pattern.compile("готовая еда|кафе|кулинария|колбаса|сосиски|деликатес").matcher(name).find();
    }

    public Measure measure(String ingredient, String label, boolean weighted) {
        if (weighted) return new Measure(1000,null);
        var match = Pattern.compile("^([0-9]+(?:[.,][0-9]+)?)\\s*(кг|г|мл|л|шт)\\.?$").matcher(normalize(label));
        if (!match.matches()) throw new IllegalArgumentException("UNSUPPORTED_SALES_UNIT");
        BigDecimal amount = new BigDecimal(match.group(1).replace(',','.'));
        String unit=match.group(2), key=normalize(ingredient), note=null;
        if (unit.equals("кг")) amount=amount.multiply(BigDecimal.valueOf(1000));
        if (unit.equals("мл") || unit.equals("л")) {
            if (unit.equals("л")) amount=amount.multiply(BigDecimal.valueOf(1000));
            BigDecimal density;
            if (key.contains("молоко")) density=new BigDecimal("1.03");
            else if (key.contains("масло подсолнечное")) density=new BigDecimal("0.92");
            else throw new IllegalArgumentException("DENSITY_UNKNOWN");
            amount=amount.multiply(density);
            note="Масса приблизительная: пересчёт объёма по плотности " + density + " г/мл.";
        }
        if (unit.equals("шт")) {
            if (!key.contains("яйцо")) throw new IllegalArgumentException("UNIT_MASS_UNKNOWN");
            amount=amount.multiply(BigDecimal.valueOf(50));
            note="Приблизительно 50 г съедобной части на одно яйцо; размер яиц различается.";
        }
        if (key.contains("тунец")) {
            amount=amount.multiply(new BigDecimal("0.70"));
            note="Съедобная масса приблизительная: принято 70% массы нетто после слива жидкости.";
        } else if (key.contains("фасоль")) {
            amount=amount.multiply(new BigDecimal("0.60"));
            note="Съедобная масса приблизительная: принято 60% массы нетто после слива жидкости.";
        }
        int grams=amount.setScale(0,RoundingMode.HALF_UP).intValueExact();
        if (grams<=0 || grams>100000) throw new IllegalArgumentException("INVALID_WEIGHT");
        return new Measure(grams,note);
    }

    private boolean matchesFat(String ingredient, String title) {
        var fat=Pattern.compile("[0-9]+(?:[.,][0-9]+)?%").matcher(ingredient);
        if (!fat.find()) return true;
        var expected = new BigDecimal(fat.group().replace("%", "").replace(',', '.'));
        var actual = Pattern.compile("[0-9]+(?:[.,][0-9]+)?%").matcher(title);
        while (actual.find()) {
            var value = new BigDecimal(actual.group().replace("%", "").replace(',', '.'));
            if (value.compareTo(expected) == 0) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('ё','е').replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }

    public record Measure(int grams, String note) {}
}
