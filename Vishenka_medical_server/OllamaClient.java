import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class OllamaClient {
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    private static final Gson gson = new Gson();

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";

    public static CompletableFuture<String> send(String fullPrompt) {
        String systemInstruction = """
            Ты - опытный ассистент врача-терапевта. Анализируй жалобу и прошлые болезни пациента (Карточки). 
            Карточки прошлых болезней лежат после основного текста врача после "||| КАРТОЧКИ ПРОШЛЫХ БОЛЕЗНЕЙ"
            Анализируешь личную карту пациента (ОНА НАХОДИТСЯ ПОСЛЕ ОПИСАНИЯ БОЛЕЗНИ НА КОТОРУЮ ПАЦИЕНТ ЖАЛУЕТСЯ ПРЯМО СЕЙЧАС), только аллергию, пол и хронические заболевания
            Если у пациента нет прошлых задокументированных болезней, будет стоять пометка "ND". Между 
            карточками, некоторыми пометками и прочим стоит разделитель "|||". Ты НЕ ставишь
            финальный диагноз, а лишь подсвечиваешь риски. Пиши строгим текстом без приветствий. 

            ЖЕСТКИЕ ПРАВИЛА:
            1. КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО пересказывать жалобы, писать вводные слова и блок "Рекомендации". Врачу нужен только сухой анализ опасностей.
            2. Флаг IMP ставь в значение "true" ТОЛЬКО если подозреваемая болезнь критична и может повлечь тяжелые последствия в будущем. В остальных случаях пиши "false".
            3. Разделители перед названием болезни и флагом IMP - это строго "|||||". СТРОГО 5 ВЕРТИКАЛЬНЫХ ПАЛОЧЕК "|||||".
            4. Анализируешь карту пациента, только аллергию и хронические заболевания
            5. Сразу после списка рисков ОБЯЗАТЕЛЬНО пиши строку: Связь с прошлыми визитами = true/false.

            ПРИМЕР ИДЕАЛЬНОГО ОТВЕТА (ПИШИ СТРОГО В ТАКОМ ФОРМАТЕ):
            Риски:
            - Риск развития острого стеноза гортани или паратонзиллярного абсцесса.
            - Риск генерализации инфекции из-за фебрильной лихорадки.
            Связь с прошлыми визитами = false
            |||||
            Название Болезни - Подозрение на ОРВИ / Ангину
            |||||
            IMP = false

            КОНЕЦ ПРИМЕРА. Твой ответ на новые данные должен выглядеть ТОЧНО ТАК ЖЕ, БЕЗ ЛИШНИХ СЛОВ!
        """;

        // 1. УБИРАЕМ склейку combinedPrompt, она больше не нужна!

        // 2. Полностью переписываем сборку JSON в методе send
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", "qwen2.5:14b");
        requestJson.addProperty("stream", false);

        // Создаем массив сообщений, который Ollama принудительно разделяет
        JsonArray messages = new JsonArray();

        // Слой СИСТЕМЫ (инструкции, правила, палочки) - ИИ обязан им подчиняться
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemInstruction);
        messages.add(systemMessage);

        // Слой ПОЛЬЗОВАТЕЛЯ (текущие жалобы + карточки из БД)
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", fullPrompt); // Передаем чистый fullPrompt!
        messages.add(userMessage);

        requestJson.add("messages", messages);

        // Настройки жесткости
        JsonObject optionsJson = new JsonObject();
        optionsJson.addProperty("temperature", 0.0); // Только 0.0!
        optionsJson.addProperty("num_ctx", 16384);
        requestJson.add("options", optionsJson);

        String jsonRequestBody = gson.toJson(requestJson);

        java.net.http.HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        return httpClient.sendAsync(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
                .thenApply(rs -> {
                    if (rs.statusCode() == 200) {
                        JsonObject responseJson = gson.fromJson(rs.body(), JsonObject.class);
                        return responseJson.getAsJsonObject("message").get("content").getAsString();
                    } else {
                        return "NO";
                    }
                })
                .exceptionally(ex -> "Ошибка" + ex.getMessage());
    }
}
