import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class AutoDocUtils {
    String card_template = """
            ================================================================
                                МЕДИЦИНСКАЯ КАРТА ПАЦИЕНТА
                                Документ заболевания (эпизод)
            ================================================================

            СНИЛС пациента:        <SNILS>
            Номер ОМС:             <OMS>
            ФИО пациента:          <FIO>
            Дата рождения:         <BIRTH_DATE>
            Пол:                   <GENDER>

            ----------------------------------------------------------------
            1. ДИАГНОЗ (name)
            ----------------------------------------------------------------
            <NAME>

            ----------------------------------------------------------------
            2. ЖАЛОБЫ И АНАМНЕЗ (description)
            ----------------------------------------------------------------
            <DESCRIPTION>

            ----------------------------------------------------------------
            3. НАЗНАЧЕНИЯ И РЕКОМЕНДАЦИИ (note)
            ----------------------------------------------------------------
            <NOTE>

            ----------------------------------------------------------------
            4. КРИТИЧНОСТЬ (is_importrant)
            ----------------------------------------------------------------
            <IS_IMPORTANT>

            ----------------------------------------------------------------
            5. СЛУЖЕБНАЯ ИНФОРМАЦИЯ
            ----------------------------------------------------------------
            Дата создания записи:   <TIMESTAMP>
            Врач / источник:        <DOCTOR>
            ================================================================
            """;
    
    public String replace_doc(String snils, String oms, String fio, String birth_date, String gender, String description, String note, String isImportant, String name, String doctor) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        String doc = card_template
            .replace("<SNILS>",      snils)
            .replace("<OMS>",        oms)
            .replace("<FIO>",        fio)
            .replace("<BIRTH_DATE>", birth_date)
            .replace("<GENDER>",     gender)
            .replace("<NAME>",       name)
            .replace("<DESCRIPTION>", description)
            .replace("<NOTE>",       note)
            .replace("<IS_IMPORTANT>", isImportant)
            .replace("<TIMESTAMP>", date)
            .replace("<DOCTOR>", doctor);

        create_doc_txt(doc, fio, name);

        return doc;
    }

    public void create_doc_txt(String doc, String fio, String name) {
        try {
            String fileName = fio + "_" + name + "_" + UUID.randomUUID().toString();
            Path path = Path.of(fileName + ".txt");
            Files.writeString(path, doc);
        } catch (IOException e) {
            System.out.println("Ошибка записи файла - " + e.getMessage());
        }
    }
}
