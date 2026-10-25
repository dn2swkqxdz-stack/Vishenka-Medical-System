import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.IdentityKeyStore.Direction;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

/**
 * Swing BIO-клиент для сервера из папки server.
 * Протокол сервера требует одно постоянное TCP-соединение и разделитель полей |||.
 */
public final class BioClient {
    private static final String CARD_CONNECTION = "connection";
    private static final String CARD_PATIENT = "patient";
    private static final String CARD_ANALYSIS = "analysis";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int MEDICAL_SERVER_PORT = 6667;
    private static final int MAIN_SERVER_PORT = 7006;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_RESPONSE_TIMEOUT_MS = 20_000;
    private static final int ANALYSIS_TIMEOUT_MS = 130_000;
    private static final int REGISTRATION_WAIT_MS = 3_000;

    private static JFrame frame;
    private static CardLayout cards;
    private static JPanel cardPanel;
    private static JLabel connectionStatus;
    private static JLabel patientStatus;
    private static JLabel analysisStatus;
    private static JTextField snilsField;
    private static JTextField omsField;
    private static JTextField fioField;
    private static JTextField emailField;
    private static JTextField birthDateField;
    private static JComboBox<String> genderBox;
    private static JTextField allergiesField;
    private static JTextField chronicDiseasesField;
    private static JTextField emergencyContactField;
    private static JTextArea complaintArea;
    private static JTextArea resultArea;
    private static JTextField diseaseNameField;
    private static JTextArea diseaseDescriptionArea;
    private static JTextArea diseaseNoteArea;
    private static JCheckBox importantBox;
    private static JButton connectButton;
    private static JButton findPatientButton;
    private static JButton registerButton;
    private static JButton toAnalysisButton;
    private static JButton analyzeButton;
    private static JButton saveCardButton;
    private static JButton backToPatientButton;
    private static JButton openMessengerButton;

    private static Socket socket;
    private static BufferedReader reader;
    private static BufferedWriter writer;
    private static boolean patientReady;
    private static boolean analysisInProgress;

    // У мессенджера своё соединение: оно не влияет на состояния медицинского сервера.
    private static JFrame messengerFrame;
    private static JTabbedPane messengerTabs;
    private static JLabel messengerStatus;
    private static JTextField messengerAccessKeyField;
    private static JTextField messengerPassportField;
    private static JPasswordField messengerPasswordField;
    private static JTextField messengerEmailField;
    private static JButton messengerLoginButton;
    private static JTextField messengerPeerField;
    private static JTextArea messengerHistoryArea;
    private static JTextArea messengerMessageArea;
    private static JButton establishSessionButton;
    private static JButton loadOfflineButton;
    private static JButton sendMessageButton;

    private static Socket messengerSocket;
    private static BufferedReader messengerReader;
    private static BufferedWriter messengerWriter;
    private static Thread messengerListener;
    private static final BlockingQueue<String> messengerResponses = new LinkedBlockingQueue<>();
    private static String messengerPassport = "";
    private static String messengerEmail = "";
    private static Connection messengerDb;
    private static final Object MESSENGER_DB_LOCK = new Object();

    private static final AutoDocUtils AUTO_DOC_UTILS = new AutoDocUtils();

    private BioClient() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BioClient::createAndShowUi);
    }

    private static void createAndShowUi() {
        setLookAndFeel();

        frame = new JFrame("BIO — карта пациента");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 620));
        frame.setSize(960, 720);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeConnection();
                closeMessengerConnection();
            }
        });

        cards = new CardLayout();
        cardPanel = new JPanel(cards);
        cardPanel.add(createConnectionPanel(), CARD_CONNECTION);
        cardPanel.add(createPatientPanel(), CARD_PATIENT);
        cardPanel.add(createAnalysisPanel(), CARD_ANALYSIS);
        frame.setContentPane(cardPanel);
        cards.show(cardPanel, CARD_CONNECTION);
        frame.setVisible(true);
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Стандартная тема Swing подходит как резервный вариант.
        }
    }

    private static JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(35, 45, 35, 45));
        GridBagConstraints c = constraints(0, 0);
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;

        JLabel title = new JLabel("BIO-клиент", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        panel.add(title, c);

        c = constraints(0, 1);
        c.gridwidth = 2;
        c.insets = new Insets(9, 5, 26, 5);
        JLabel subtitle = new JLabel("Выберите нужный модуль", SwingConstants.CENTER);
        subtitle.setForeground(new Color(80, 80, 80));
        panel.add(subtitle, c);

        connectButton = new JButton("Работа с пациентами");
        connectButton.addActionListener(event -> connect());
        JButton messengerFromStartButton = new JButton("Открыть мессенджер");
        messengerFromStartButton.addActionListener(event -> openMessenger());
        JPanel connectionActions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        connectionActions.add(connectButton);
        connectionActions.add(messengerFromStartButton);
        c = constraints(0, 2);
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(20, 5, 12, 5);
        panel.add(connectionActions, c);

        connectionStatus = new JLabel("Серверы выбираются автоматически.", SwingConstants.CENTER);
        c = constraints(0, 3);
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(connectionStatus, c);
        return panel;
    }

    private static JPanel createPatientPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        JLabel title = new JLabel("Пациент");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        header.add(title, BorderLayout.WEST);
        patientStatus = new JLabel("Подключено. Укажите СНИЛС пациента.");
        header.add(patientStatus, BorderLayout.CENTER);
        openMessengerButton = new JButton("Мессенджер");
        openMessengerButton.addActionListener(event -> openMessenger());
        JButton disconnectButton = new JButton("Отключиться");
        disconnectButton.addActionListener(event -> disconnect());
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        headerActions.add(openMessengerButton);
        headerActions.add(disconnectButton);
        header.add(headerActions, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        body.setBorder(BorderFactory.createTitledBorder("Поиск или регистрация"));
        snilsField = new JTextField(22);
        addLabelAndField(body, "СНИЛС", snilsField, 0);
        JLabel hint = new JLabel("Допустимы 11 цифр или формат 123-456-789 09");
        hint.setForeground(new Color(100, 100, 100));
        GridBagConstraints hintConstraints = constraints(1, 1);
        hintConstraints.anchor = GridBagConstraints.WEST;
        body.add(hint, hintConstraints);

        findPatientButton = new JButton("Найти пациента");
        findPatientButton.addActionListener(event -> findPatient());
        GridBagConstraints findConstraints = constraints(1, 2);
        findConstraints.anchor = GridBagConstraints.WEST;
        findConstraints.insets = new Insets(10, 5, 16, 5);
        body.add(findPatientButton, findConstraints);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Данные пациента"));
        omsField = new JTextField(26);
        fioField = new JTextField(26);
        emailField = new JTextField(26);
        birthDateField = new JTextField(26);
        genderBox = new JComboBox<>(new String[]{"Не указан", "Мужской", "Женский"});
        allergiesField = new JTextField(26);
        chronicDiseasesField = new JTextField(26);
        emergencyContactField = new JTextField(26);
        addLabelAndField(form, "Номер ОМС", omsField, 0);
        addLabelAndField(form, "ФИО", fioField, 1);
        addLabelAndField(form, "E-mail", emailField, 2);
        addLabelAndField(form, "Дата рождения", birthDateField, 3);
        addLabelAndField(form, "Пол", genderBox, 4);
        addLabelAndField(form, "Аллергии", allergiesField, 5);
        addLabelAndField(form, "Хронические заболевания", chronicDiseasesField, 6);
        addLabelAndField(form, "Экстренный контакт", emergencyContactField, 7);

        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.add(body, BorderLayout.NORTH);
        center.add(form, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);

        registerButton = new JButton("Зарегистрировать пациента");
        registerButton.addActionListener(event -> registerPatient());
        toAnalysisButton = new JButton("Перейти к анализу жалобы");
        toAnalysisButton.addActionListener(event -> cards.show(cardPanel, CARD_ANALYSIS));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(registerButton);
        actions.add(toAnalysisButton);
        panel.add(actions, BorderLayout.SOUTH);

        setProfileEditable(false);
        return panel;
    }

    private static JPanel createAnalysisPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        JLabel title = new JLabel("Анализ жалобы");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        header.add(title, BorderLayout.WEST);
        analysisStatus = new JLabel("Опишите текущие жалобы пациента.");
        header.add(analysisStatus, BorderLayout.CENTER);
        backToPatientButton = new JButton("К пациенту");
        backToPatientButton.addActionListener(event -> cards.show(cardPanel, CARD_PATIENT));
        header.add(backToPatientButton, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        complaintArea = new JTextArea(5, 50);
        complaintArea.setLineWrap(true);
        complaintArea.setWrapStyleWord(true);
        JPanel complaintPanel = new JPanel(new BorderLayout(5, 5));
        complaintPanel.setBorder(BorderFactory.createTitledBorder("Текущие жалобы"));
        complaintPanel.add(new JScrollPane(complaintArea), BorderLayout.CENTER);
        analyzeButton = new JButton("Запустить анализ");
        analyzeButton.addActionListener(event -> analyzeComplaint());
        JPanel analyzeAction = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        analyzeAction.add(analyzeButton);
        complaintPanel.add(analyzeAction, BorderLayout.SOUTH);

        resultArea = new JTextArea();
        resultArea.setEditable(false);
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.setBorder(BorderFactory.createTitledBorder("Результат анализа"));
        resultPanel.add(new JScrollPane(resultArea), BorderLayout.CENTER);

        JPanel upper = new JPanel(new GridLayout(2, 1, 0, 10));
        upper.add(complaintPanel);
        upper.add(resultPanel);

        JPanel cardForm = new JPanel(new GridBagLayout());
        cardForm.setBorder(BorderFactory.createTitledBorder("Сохранение карточки посещения"));
        diseaseNameField = new JTextField(38);
        diseaseDescriptionArea = new JTextArea(3, 38);
        diseaseDescriptionArea.setLineWrap(true);
        diseaseDescriptionArea.setWrapStyleWord(true);
        diseaseNoteArea = new JTextArea(2, 38);
        diseaseNoteArea.setLineWrap(true);
        diseaseNoteArea.setWrapStyleWord(true);
        importantBox = new JCheckBox("Важная карточка");
        addLabelAndField(cardForm, "Название", diseaseNameField, 0);
        addLabelAndField(cardForm, "Описание", new JScrollPane(diseaseDescriptionArea), 1);
        addLabelAndField(cardForm, "Заметка врача", new JScrollPane(diseaseNoteArea), 2);
        GridBagConstraints importantConstraints = constraints(1, 3);
        importantConstraints.anchor = GridBagConstraints.WEST;
        cardForm.add(importantBox, importantConstraints);
        saveCardButton = new JButton("Сохранить карточку");
        saveCardButton.addActionListener(event -> saveVisitCard());
        GridBagConstraints saveConstraints = constraints(1, 4);
        saveConstraints.anchor = GridBagConstraints.EAST;
        cardForm.add(saveCardButton, saveConstraints);

        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.add(upper, BorderLayout.CENTER);
        center.add(cardForm, BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);
        setCardFieldsEnabled(false);
        return panel;
    }

    private static void connect() {
        connectButton.setEnabled(false);
        connectionStatus.setText("Подключение к серверу пациентов…");
        runAsync(() -> {
            closeConnection();
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(LOCAL_HOST, MEDICAL_SERVER_PORT), CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);
            socket = newSocket;
            reader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
            return null;
        }, ignored -> {
            patientReady = false;
            clearPatientForm();
            setProfileEditable(false);
            patientStatus.setText("Подключено. Укажите СНИЛС пациента.");
            cards.show(cardPanel, CARD_PATIENT);
        }, "Не удалось подключиться к серверу");
    }

    private static void disconnect() {
        closeConnection();
        patientReady = false;
        clearPatientForm();
        setProfileEditable(false);
        connectionStatus.setText("Соединение закрыто.");
        connectButton.setEnabled(true);
        cards.show(cardPanel, CARD_CONNECTION);
    }

    private static void findPatient() {
        if (!isConnected()) {
            showError("Нет соединения с сервером.");
            return;
        }

        final String snils;
        try {
            snils = normalizeSnils(snilsField.getText());
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        snilsField.setText(snils);
        patientReady = false;
        setProfileEditable(false);
        patientStatus.setText("Поиск пациента…");
        runAsync(() -> {
            send("PATIENT_OPTION|||" + snils);
            return readOptionalLine(REGISTRATION_WAIT_MS);
        }, response -> handlePatientSearchResponse(response, snils), "Не удалось получить данные пациента");
    }

    private static void handlePatientSearchResponse(String response, String snils) {
        if (response == null) {
            // Сервер не посылает отдельного ответа NOT_EXISTS, но переводит соединение
            // в состояние ожидания регистрационных данных.
            clearProfileFields();
            setProfileEditable(true);
            registerButton.setEnabled(true);
            patientStatus.setText("Пациент не найден. Заполните данные и зарегистрируйте его.");
            return;
        }

        if (response.startsWith("GET_CURRENT_PATIENT---")) {
            String rawData = response.substring("GET_CURRENT_PATIENT---".length());
            String[] data = rawData.split("\\|\\|\\|", -1);
            if (data.length != 8) {
                showError("Сервер вернул карточку пациента в неизвестном формате.");
                return;
            }
            omsField.setText(data[0]);
            fioField.setText(data[1]);
            emailField.setText(data[2]);
            birthDateField.setText(data[3]);
            genderBox.setSelectedItem(data[4]);
            if (genderBox.getSelectedIndex() < 0) {
                genderBox.setSelectedItem("Не указан");
            }
            allergiesField.setText(data[5]);
            chronicDiseasesField.setText(data[6]);
            emergencyContactField.setText(data[7]);
            setProfileEditable(false);
            patientReady = true;
            toAnalysisButton.setEnabled(true);
            patientStatus.setText("Карточка пациента найдена.");
            return;
        }

        if ("INVALID_SNILS".equals(response)) {
            showError("Сервер не принял СНИЛС: проверьте контрольное число.");
        } else if ("SERVER_ERR".equals(response) || "NO_SERVER_ERR".equals(response)) {
            showError("Сервер сообщил об ошибке при обработке пациента.");
        } else {
            showError("Неожиданный ответ сервера: " + response);
        }
        patientStatus.setText("Поиск не выполнен.");
    }

    private static void registerPatient() {
        if (!isConnected()) {
            showError("Нет соединения с сервером.");
            return;
        }

        final String snils;
        final String oms;
        final String fio;
        final String email;
        final String birthDate;
        final String gender;
        final String allergies;
        final String chronic;
        final String emergency;
        try {
            snils = normalizeSnils(snilsField.getText());
            oms = protocolValue(omsField.getText(), "Номер ОМС");
            fio = protocolValue(fioField.getText(), "ФИО");
            email = protocolValue(emailField.getText(), "E-mail");
            birthDate = protocolValue(birthDateField.getText(), "Дата рождения");
            gender = protocolValue(String.valueOf(genderBox.getSelectedItem()), "Пол");
            allergies = protocolValue(allergiesField.getText(), "Аллергии");
            chronic = protocolValue(chronicDiseasesField.getText(), "Хронические заболевания");
            emergency = protocolValue(emergencyContactField.getText(), "Экстренный контакт");
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        patientStatus.setText("Регистрация пациента…");
        runAsync(() -> {
            send("PATIENT_OPTION|||" + snils + "|||" + oms + "|||" + fio + "|||" + email + "|||"
                    + birthDate + "|||" + gender + "|||" + allergies + "|||" + chronic + "|||" + emergency);
            return readLine(DEFAULT_RESPONSE_TIMEOUT_MS);
        }, response -> {
            if ("YES".equals(response)) {
                patientReady = true;
                setProfileEditable(false);
                toAnalysisButton.setEnabled(true);
                patientStatus.setText("Пациент зарегистрирован.");
            } else {
                showError("Не удалось зарегистрировать пациента: " + response);
                patientStatus.setText("Регистрация не завершена.");
            }
        }, "Не удалось зарегистрировать пациента");
    }

    private static void analyzeComplaint() {
        if (!isConnected() || !patientReady) {
            showError("Сначала найдите или зарегистрируйте пациента в этой сессии.");
            return;
        }

        final String complaint;
        final String patientInfo;
        try {
            complaint = protocolValue(complaintArea.getText(), "Жалобы");
            if (complaint.isEmpty()) {
                throw new IllegalArgumentException("Опишите текущие жалобы пациента.");
            }
            patientInfo = "Личная карта пациента. Пол: " + protocolValue(String.valueOf(genderBox.getSelectedItem()), "Пол")
                    + ". Аллергии: " + protocolValue(allergiesField.getText(), "Аллергии")
                    + ". Хронические заболевания: " + protocolValue(chronicDiseasesField.getText(), "Хронические заболевания") + ".";
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        analysisStatus.setText("Анализ выполняется — это может занять до двух минут…");
        resultArea.setText("");
        clearVisitCardFields();
        analysisInProgress = true;
        backToPatientButton.setEnabled(false);
        runAsync(() -> {
            // В TCP-протоколе сервера перевод строки означает конец команды.
            send("ANALYZE_CURRENT_PATIENT|||" + complaint + " " + patientInfo);
            String started = readLine(ANALYSIS_TIMEOUT_MS);
            if (!"ANALYZING_STARTED".equals(started)) {
                return started;
            }
            return readMultilineReply(ANALYSIS_TIMEOUT_MS);
        }, result -> {
            resultArea.setText(formatAnalysis(result));
            resultArea.setCaretPosition(0);
            // Карточка посещения заполняется врачом вручную, а не данными модели.
            clearVisitCardFields();
            setCardFieldsEnabled(true);
            analysisStatus.setText("Анализ получен. Заполните карточку посещения вручную.");
        }, "Не удалось получить результат анализа");
    }

    private static void saveVisitCard() {
        if (!isConnected() || !patientReady) {
            showError("Нет активной сессии пациента.");
            return;
        }

        final String name;
        final String description;
        final String note;
        final boolean isImportant;
        try {
            name = protocolValue(diseaseNameField.getText(), "Название карточки");
            description = protocolValue(diseaseDescriptionArea.getText(), "Описание карточки");
            note = protocolValue(diseaseNoteArea.getText(), "Заметка врача");
            isImportant = importantBox.isSelected();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Укажите название карточки посещения.");
            }
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        analysisStatus.setText("Сохранение карточки…");
        runAsync(() -> {
            send("ANALYZE_CURRENT_PATIENT|||" + name + "|||" + description + "|||" + note + "|||" + isImportant);
            return readLine(DEFAULT_RESPONSE_TIMEOUT_MS);
        }, response -> {
            if ("YES".equals(response)) {
                analysisStatus.setText("Карточка посещения сохранена.");
                setCardFieldsEnabled(false);
                showGeneratedDocument(name, description, note, isImportant);
            } else {
                showError("Сервер не сохранил карточку: " + response);
                analysisStatus.setText("Карточка не сохранена.");
            }
        }, "Не удалось сохранить карточку");
    }

    private static String formatAnalysis(String rawAnalysis) {
        String[] analysis = parseAnalysis(rawAnalysis);
        if (analysis == null) {
            return "Сервер не вернул текст анализа.";
        }

        StringBuilder formatted = new StringBuilder();
        appendResultSection(formatted, "РИСКИ", analysis[0].isEmpty() ? "Не указаны." : analysis[0]);
        appendResultSection(formatted, "ПРЕДПОЛАГАЕМОЕ СОСТОЯНИЕ",
                analysis[1].isEmpty() ? "Не указано." : analysis[1]);

        String importanceText;
        if ("true".equalsIgnoreCase(analysis[2].trim())) {
            importanceText = "Отмечено как критичное.";
        } else if ("false".equalsIgnoreCase(analysis[2].trim())) {
            importanceText = "Не отмечено как критичное.";
        } else {
            importanceText = analysis[2].isEmpty() ? "Не указана." : analysis[2];
        }
        appendResultSection(formatted, "ВАЖНОСТЬ", importanceText);
        return formatted.toString();
    }

    /** Возвращает: риски, предполагаемое состояние и флаг важности. */
    private static String[] parseAnalysis(String rawAnalysis) {
        String raw = rawAnalysis == null ? "" : rawAnalysis.trim();
        if (raw.isEmpty()) {
            return null;
        }

        String[] sections = raw.split("\\Q|||||\\E", -1);
        if (sections.length < 3) {
            // Модель может нарушить шаблон: показываем её текст как риски,
            // но не пытаемся угадать название и флаг для документа.
            return new String[]{raw.replace("|||||", "\n\n"), "", ""};
        }

        String risks = withoutPrefix(sections[0], "Риски:");
        String condition = withoutPrefix(sections[1], "Название Болезни -");
        StringBuilder importanceSource = new StringBuilder(sections[2].trim());
        for (int i = 3; i < sections.length; i++) {
            if (!sections[i].trim().isEmpty()) {
                importanceSource.append('\n').append(sections[i].trim());
            }
        }
        return new String[]{risks, condition, withoutPrefix(importanceSource.toString(), "IMP =")};
    }

    private static void showGeneratedDocument(String name, String description, String note, boolean isImportant) {
        String document = AUTO_DOC_UTILS.replace_doc(
                snilsField.getText(),
                omsField.getText(),
                fioField.getText(),
                birthDateField.getText(),
                String.valueOf(genderBox.getSelectedItem()),
                description,
                note,
                String.valueOf(isImportant),
                name,
                "BIO-клиент"
        );

        JTextArea documentArea = new JTextArea(document, 26, 68);
        documentArea.setEditable(false);
        documentArea.setLineWrap(true);
        documentArea.setWrapStyleWord(true);
        documentArea.setCaretPosition(0);
        JScrollPane documentScroll = new JScrollPane(documentArea);
        documentScroll.setPreferredSize(new Dimension(700, 520));
        JOptionPane.showMessageDialog(
                frame,
                documentScroll,
                "Сформированный документ посещения",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    // -------------------------------------------------------------------------
    // Мессенджер: отдельный Swing-интерфейс и тот же Signal-протокол, что в
    // example_client. Соединение не связано с socket/reader/writer медклиента.
    // -------------------------------------------------------------------------

    private static void openMessenger() {
        if (messengerFrame == null) {
            createMessengerWindow();
        }
        messengerFrame.setLocationRelativeTo(frame);
        messengerFrame.setVisible(true);
        messengerFrame.toFront();
    }

    private static void createMessengerWindow() {
        messengerFrame = new JFrame("BIO — защищённый мессенджер");
        messengerFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        messengerFrame.setMinimumSize(new Dimension(760, 560));
        messengerFrame.setSize(900, 680);

        messengerTabs = new JTabbedPane();
        messengerTabs.addTab("Вход", createMessengerLoginPanel());
        messengerTabs.addTab("Диалог", createMessengerChatPanel());
        messengerTabs.setEnabledAt(1, false);
        messengerFrame.setContentPane(messengerTabs);
    }

    private static JPanel createMessengerLoginPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(28, 42, 28, 42));

        GridBagConstraints c = constraints(0, 0);
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        JLabel title = new JLabel("Защищённый мессенджер", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        panel.add(title, c);

        c = constraints(0, 1);
        c.gridwidth = 2;
        c.insets = new Insets(7, 5, 24, 5);
        JLabel subtitle = new JLabel("Сначала запрашивается сервер у главного узла, как в example_client", SwingConstants.CENTER);
        subtitle.setForeground(new Color(80, 80, 80));
        panel.add(subtitle, c);

        messengerAccessKeyField = new JTextField(24);
        messengerPassportField = new JTextField(24);
        messengerPasswordField = new JPasswordField(24);
        messengerEmailField = new JTextField(24);
        addLabelAndField(panel, "Ключ мессенджера", messengerAccessKeyField, 2);
        addLabelAndField(panel, "Номер паспорта", messengerPassportField, 3);
        addLabelAndField(panel, "Пароль", messengerPasswordField, 4);
        addLabelAndField(panel, "E-mail", messengerEmailField, 5);

        messengerLoginButton = new JButton("Получить сервер и войти");
        messengerLoginButton.addActionListener(event -> loginMessenger());
        c = constraints(0, 6);
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(20, 5, 6, 5);
        panel.add(messengerLoginButton, c);

        messengerStatus = new JLabel("Адреса и порты серверов вводить не нужно.", SwingConstants.CENTER);
        c = constraints(0, 7);
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(messengerStatus, c);
        return panel;
    }

    private static JPanel createMessengerChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel peerPanel = new JPanel(new GridBagLayout());
        peerPanel.setBorder(BorderFactory.createTitledBorder("Собеседник"));
        messengerPeerField = new JTextField(30);
        addLabelAndField(peerPanel, "E-mail", messengerPeerField, 0);
        establishSessionButton = new JButton("Начать защищённый чат");
        establishSessionButton.addActionListener(event -> establishMessengerSession());
        loadOfflineButton = new JButton("Загрузить офлайн-сообщения");
        loadOfflineButton.addActionListener(event -> loadOfflineMessages());
        GridBagConstraints buttons = constraints(1, 1);
        buttons.anchor = GridBagConstraints.WEST;
        peerPanel.add(establishSessionButton, buttons);
        buttons = constraints(1, 2);
        buttons.anchor = GridBagConstraints.WEST;
        peerPanel.add(loadOfflineButton, buttons);
        panel.add(peerPanel, BorderLayout.NORTH);

        messengerHistoryArea = new JTextArea();
        messengerHistoryArea.setEditable(false);
        messengerHistoryArea.setLineWrap(true);
        messengerHistoryArea.setWrapStyleWord(true);
        JScrollPane historyScroll = new JScrollPane(messengerHistoryArea);
        historyScroll.setBorder(BorderFactory.createTitledBorder("Сообщения"));

        messengerMessageArea = new JTextArea(4, 52);
        messengerMessageArea.setLineWrap(true);
        messengerMessageArea.setWrapStyleWord(true);
        JScrollPane composeScroll = new JScrollPane(messengerMessageArea);
        composeScroll.setBorder(BorderFactory.createTitledBorder("Новое сообщение"));
        sendMessageButton = new JButton("Отправить");
        sendMessageButton.addActionListener(event -> sendMessengerMessage());
        JPanel composePanel = new JPanel(new BorderLayout(5, 5));
        composePanel.add(composeScroll, BorderLayout.CENTER);
        JPanel sendPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        sendPanel.add(sendMessageButton);
        composePanel.add(sendPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, historyScroll, composePanel);
        split.setResizeWeight(0.75);
        split.setDividerLocation(355);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private static void loginMessenger() {
        final String accessKey;
        final String passport;
        final String password;
        final String email;
        try {
            accessKey = messengerProtocolValue(messengerAccessKeyField.getText(), "Ключ мессенджера");
            passport = messengerProtocolValue(messengerPassportField.getText(), "Номер паспорта");
            password = messengerProtocolValue(new String(messengerPasswordField.getPassword()), "Пароль");
            email = messengerProtocolValue(messengerEmailField.getText(), "E-mail");
            if (accessKey.isEmpty() || passport.isEmpty() || password.isEmpty() || email.isEmpty()) {
                throw new IllegalArgumentException("Заполните ключ мессенджера, паспорт, пароль и e-mail.");
            }
        } catch (IllegalArgumentException exception) {
            showMessengerError(exception.getMessage());
            return;
        }

        messengerStatus.setText("Получаем сервер мессенджера у главного сервера…");
        runMessengerAsync(() -> {
            openMessengerStore();
            connectMessengerViaMainServer(accessKey);
            sendMessenger("LOGIN|||" + passport + "|||" + password + "|||" + email);
            String response = readMessengerDirect(DEFAULT_RESPONSE_TIMEOUT_MS);
            if ("BAD_CREDENTIALS".equals(response) || "NO_USER".equals(response)
                    || "ERROR_FORMAT".equals(response)) {
                throw new IOException("Сервер отклонил учётные данные: " + response);
            }
            if (!response.startsWith("YES") && !response.startsWith("NEED_TO_GENERATE")) {
                throw new IOException("Неожиданный ответ сервера: " + response);
            }

            messengerPassport = passport;
            messengerEmail = email;
            if (response.startsWith("NEED_TO_GENERATE") || !hasMessengerLocalKeys(passport)) {
                generateAndUploadMessengerKeys(passport, email);
            }
            startMessengerListener();
            return response;
        }, response -> {
            messengerTabs.setEnabledAt(1, true);
            messengerTabs.setSelectedIndex(1);
            messengerStatus.setText("Вход выполнен: " + messengerEmail + ".");
            appendMessengerLine("Система: защищённый канал готов. Выберите собеседника и начните сессию.");
        }, "Не удалось войти в мессенджер");
    }

    private static void establishMessengerSession() {
        final String peer;
        try {
            peer = messengerProtocolValue(messengerPeerField.getText(), "E-mail собеседника");
            if (peer.isEmpty()) {
                throw new IllegalArgumentException("Укажите e-mail собеседника.");
            }
        } catch (IllegalArgumentException exception) {
            showMessengerError(exception.getMessage());
            return;
        }

        messengerStatus.setText("Запрашиваем публичные ключи собеседника…");
        runMessengerAsync(() -> {
            createMessengerSession(peer);
            return peer;
        }, ignored -> {
            messengerStatus.setText("Защищённая сессия с " + peer + " готова.");
            appendMessengerLine("Система: защищённая сессия с " + peer + " установлена.");
        }, "Не удалось создать защищённую сессию");
    }

    private static void sendMessengerMessage() {
        final String peer;
        final String text;
        try {
            peer = messengerProtocolValue(messengerPeerField.getText(), "E-mail собеседника");
            text = messengerProtocolValue(messengerMessageArea.getText(), "Сообщение");
            if (peer.isEmpty() || text.isEmpty()) {
                throw new IllegalArgumentException("Укажите собеседника и текст сообщения.");
            }
            if (text.getBytes(StandardCharsets.UTF_8).length > 3_500) {
                throw new IllegalArgumentException("Сообщение слишком длинное для протокола сервера (максимум 3500 байт UTF-8).");
            }
        } catch (IllegalArgumentException exception) {
            showMessengerError(exception.getMessage());
            return;
        }

        runMessengerAsync(() -> {
            MessengerStore store = new MessengerStore(messengerPassport);
            SignalProtocolAddress address = new SignalProtocolAddress(peer, 1);
            if (!store.containsSession(address)) {
                throw new IllegalStateException("Сессия ещё не установлена. Нажмите «Начать защищённый чат».");
            }
            SessionCipher cipher = new SessionCipher(store, address);
            CiphertextMessage encrypted = cipher.encrypt(text.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getEncoder().encodeToString(encrypted.serialize());
            sendMessenger("PM|||" + peer + "|||" + encrypted.getType() + "|||" + payload);
            return text;
        }, sentText -> {
            appendMessengerLine("Вы → " + peer + ": " + sentText);
            messengerMessageArea.setText("");
            messengerStatus.setText("Сообщение отправлено.");
        }, "Не удалось отправить сообщение");
    }

    private static void loadOfflineMessages() {
        final String peer;
        try {
            peer = messengerProtocolValue(messengerPeerField.getText(), "E-mail собеседника");
            if (peer.isEmpty()) {
                throw new IllegalArgumentException("Укажите e-mail собеседника.");
            }
        } catch (IllegalArgumentException exception) {
            showMessengerError(exception.getMessage());
            return;
        }

        messengerStatus.setText("Получаем офлайн-сообщения…");
        runMessengerAsync(() -> {
            messengerResponses.clear();
            sendMessenger("OFFLINE_MSGS|||" + peer + "|||" + messengerEmail);
            String response = takeMessengerResponse(DEFAULT_RESPONSE_TIMEOUT_MS);
            if ("NO".equals(response)) {
                return "0";
            }
            String[] tokens = response.split("\\|", -1);
            if (tokens.length % 4 != 0) {
                throw new IOException("Сервер прислал офлайн-сообщения в неверном формате.");
            }
            int loaded = 0;
            for (int i = 0; i < tokens.length; i += 4) {
                String clearText = decryptMessengerMessage(tokens[i], Integer.parseInt(tokens[i + 3]), peer);
                appendMessengerLine("[" + tokens[i + 2] + "] " + peer + " → Вы: " + clearText);
                loaded++;
            }
            return String.valueOf(loaded);
        }, count -> messengerStatus.setText("Получено офлайн-сообщений: " + count + "."),
                "Не удалось получить офлайн-сообщения");
    }

    /**
     * Ровно как в example_client: сначала обращаемся к MainServer:7006 с
     * GET_HOST, затем переподключаемся к выданному локальному серверу сообщений.
     */
    private static void connectMessengerViaMainServer(String accessKey) throws IOException {
        final String messengerPort;
        try (Socket mainSocket = new Socket()) {
            mainSocket.connect(new InetSocketAddress(LOCAL_HOST, MAIN_SERVER_PORT), CONNECT_TIMEOUT_MS);
            mainSocket.setSoTimeout(DEFAULT_RESPONSE_TIMEOUT_MS);
            try (BufferedWriter mainWriter = new BufferedWriter(
                    new OutputStreamWriter(mainSocket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader mainReader = new BufferedReader(
                    new InputStreamReader(mainSocket.getInputStream(), StandardCharsets.UTF_8))) {
                mainWriter.write("GET_HOST|||" + accessKey + "\n");
                mainWriter.flush();
                String response = mainReader.readLine();
                if (response == null) {
                    throw new IOException("Главный сервер закрыл соединение до ответа.");
                }
                String[] parts = response.split("\\|\\|\\|", -1);
                if (parts.length != 2 || !"YES".equals(parts[0])) {
                    throw new IOException("Главный сервер не принял ключ: " + response);
                }
                messengerPort = parts[1];
            }
        }

        try {
            connectMessenger(LOCAL_HOST, Integer.parseInt(messengerPort));
        } catch (NumberFormatException exception) {
            throw new IOException("Главный сервер вернул некорректный порт: " + messengerPort, exception);
        }
    }

    private static void connectMessenger(String host, int port) throws IOException {
        closeMessengerConnection();
        Socket newSocket = new Socket();
        newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        newSocket.setTcpNoDelay(true);
        messengerSocket = newSocket;
        messengerReader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
        messengerWriter = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
        messengerResponses.clear();
    }

    private static void sendMessenger(String message) throws IOException {
        synchronized (MESSENGER_IO_LOCK) {
            ensureMessengerConnected();
            messengerWriter.write(message);
            messengerWriter.write('\n');
            messengerWriter.flush();
        }
    }

    private static String readMessengerDirect(int timeoutMs) throws IOException {
        synchronized (MESSENGER_IO_LOCK) {
            ensureMessengerConnected();
            messengerSocket.setSoTimeout(timeoutMs);
            try {
                String response = messengerReader.readLine();
                if (response == null) {
                    throw new IOException("Сервер мессенджера закрыл соединение.");
                }
                return response;
            } finally {
                messengerSocket.setSoTimeout(0);
            }
        }
    }

    private static String takeMessengerResponse(int timeoutMs) throws IOException {
        try {
            String response = messengerResponses.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new SocketTimeoutException("Сервер мессенджера не ответил вовремя.");
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание ответа мессенджера было прервано.", exception);
        }
    }

    private static void startMessengerListener() {
        if (messengerListener != null && messengerListener.isAlive()) {
            return;
        }
        final Socket listeningSocket = messengerSocket;
        final BufferedReader listeningReader = messengerReader;
        messengerListener = new Thread(() -> {
            try {
                while (listeningSocket == messengerSocket && !listeningSocket.isClosed()) {
                    String received = listeningReader.readLine();
                    if (received == null) {
                        break;
                    }
                    if (received.startsWith("INCOMING_PM|||")) {
                        handleIncomingMessengerMessage(received);
                    } else {
                        messengerResponses.offer(received);
                    }
                }
            } catch (IOException exception) {
                appendMessengerLine("Система: соединение с мессенджером закрыто (" + exception.getMessage() + ").");
            }
        }, "bio-messenger-listener");
        messengerListener.setDaemon(true);
        messengerListener.start();
    }

    private static void handleIncomingMessengerMessage(String message) {
        try {
            String[] parts = message.split("\\|\\|\\|", 5);
            if (parts.length != 5) {
                throw new IOException("Неверный формат входящего сообщения.");
            }
            String clearText = decryptMessengerMessage(parts[3], Integer.parseInt(parts[2]), parts[1]);
            appendMessengerLine("[" + parts[4] + "] " + parts[1] + " → Вы: " + clearText);
        } catch (Exception exception) {
            appendMessengerLine("Система: не удалось расшифровать входящее сообщение: " + exception.getMessage());
        }
    }

    private static String decryptMessengerMessage(String payload, int messageType, String senderEmail) throws Exception {
        SignalProtocolAddress sender = new SignalProtocolAddress(senderEmail, 1);
        SessionCipher cipher = new SessionCipher(new MessengerStore(messengerPassport), sender);
        byte[] bytes = Base64.getDecoder().decode(payload.trim());
        byte[] plain;
        if (messageType == 3) {
            plain = cipher.decrypt(new PreKeySignalMessage(bytes));
        } else {
            plain = cipher.decrypt(new SignalMessage(bytes));
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static void createMessengerSession(String peerEmail) throws Exception {
        MessengerStore store = new MessengerStore(messengerPassport);
        SignalProtocolAddress peer = new SignalProtocolAddress(peerEmail, 1);
        if (store.containsSession(peer)) {
            return;
        }

        messengerResponses.clear();
        sendMessenger("GET_BUNDLE|||" + peerEmail);
        String response = takeMessengerResponse(DEFAULT_RESPONSE_TIMEOUT_MS);
        if ("KEY_ERROR".equals(response) || "NO_USER".equals(response) || "NO_KEYS".equals(response)) {
            throw new IOException("У собеседника нет доступного набора ключей: " + response);
        }

        String[] parts = response.split("\\|\\|\\|");
        final IdentityKey identityKey;
        final int signedPreKeyId;
        final ECPublicKey signedPreKey;
        final byte[] signature;
        final int oneTimePreKeyId;
        final ECPublicKey oneTimePreKey;

        if (parts.length == 4 && "KEYS_FOR_X2DX".equals(parts[0])) {
            identityKey = new IdentityKey(Base64.getDecoder().decode(parts[1]), 0);
            String[] signed = parts[2].split("\\*\\*\\*", 2);
            signedPreKeyId = Integer.parseInt(signed[0]);
            signedPreKey = Curve.decodePoint(Base64.getDecoder().decode(signed[1]), 0);
            signature = Base64.getDecoder().decode(parts[3]);
            oneTimePreKeyId = 0;
            oneTimePreKey = null;
        } else if (parts.length >= 5 && "KEYS_FOR_X3DX".equals(parts[0])) {
            String[] oneTime = parts[1].split("\\*\\*\\*", 2);
            oneTimePreKeyId = Integer.parseInt(oneTime[0]);
            oneTimePreKey = Curve.decodePoint(Base64.getDecoder().decode(oneTime[1]), 0);
            identityKey = new IdentityKey(Base64.getDecoder().decode(parts[2]), 0);
            String[] signed = parts[3].split("\\*\\*\\*", 2);
            signedPreKeyId = Integer.parseInt(signed[0]);
            signedPreKey = Curve.decodePoint(Base64.getDecoder().decode(signed[1]), 0);
            signature = Base64.getDecoder().decode(parts[4]);
        } else {
            throw new IOException("Неподдерживаемый формат набора ключей: " + response);
        }

        if (!ensureMessengerTrustedIdentity(messengerPassport, peerEmail, identityKey.getPublicKey().serialize())) {
            throw new SecurityException("Ключ идентификации собеседника не совпадает с сохранённым ключом.");
        }
        PreKeyBundle bundle = new PreKeyBundle(0, 1, oneTimePreKeyId, oneTimePreKey,
                signedPreKeyId, signedPreKey, signature, identityKey);
        new SessionBuilder(store, peer).process(bundle);
    }

    private static void generateAndUploadMessengerKeys(String passport, String email) throws Exception {
        resetMessengerLocalKeys(passport);
        ECKeyPair identityPair = Curve.generateKeyPair();
        ECKeyPair x25519Pair = Curve.generateKeyPair();
        ECKeyPair signedPreKeyPair = Curve.generateKeyPair();
        int signedPreKeyId = 1;
        byte[] signature = Curve.calculateSignature(identityPair.getPrivateKey(), signedPreKeyPair.getPublicKey().serialize());

        saveMessengerPrivateKeys(passport,
                identityPair.getPrivateKey().serialize(),
                x25519Pair.getPrivateKey().serialize(),
                identityPair.getPublicKey().serialize(),
                x25519Pair.getPublicKey().serialize());
        SignedPreKeyRecord signedRecord = new SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(),
                signedPreKeyPair, signature);
        saveMessengerPreKey(passport, "SIGNED", signedPreKeyId, signedRecord.serialize());

        StringBuilder publicOneTimeKeys = new StringBuilder();
        for (int keyId = 1; keyId <= 50; keyId++) {
            ECKeyPair keyPair = Curve.generateKeyPair();
            PreKeyRecord record = new PreKeyRecord(keyId, keyPair);
            saveMessengerPreKey(passport, "ONE_TIME", keyId, record.serialize());
            if (!publicOneTimeKeys.isEmpty()) {
                publicOneTimeKeys.append("***");
            }
            publicOneTimeKeys.append(keyId).append(',')
                    .append(Base64.getEncoder().encodeToString(keyPair.getPublicKey().serialize()));
        }

        sendMessenger("PUT_KEYS|||"
                + Base64.getEncoder().encodeToString(identityPair.getPublicKey().serialize()) + "|||"
                + Base64.getEncoder().encodeToString(x25519Pair.getPublicKey().serialize()) + "|||"
                + signedPreKeyId + "|||"
                + Base64.getEncoder().encodeToString(signedPreKeyPair.getPublicKey().serialize()) + "|||"
                + Base64.getEncoder().encodeToString(signature) + "|||"
                + passport + "|||" + email + "|||" + publicOneTimeKeys);
        String response = readMessengerDirect(DEFAULT_RESPONSE_TIMEOUT_MS);
        if (!"OK".equals(response)) {
            throw new IOException("Сервер не сохранил публичные ключи: " + response);
        }
    }

    private static void runMessengerAsync(Callable<String> task, Consumer<String> onSuccess, String errorPrefix) {
        setMessengerControlsEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    messengerStatus.setText("Операция не выполнена.");
                    showMessengerError(errorPrefix + ": " + cause.getMessage());
                } finally {
                    setMessengerControlsEnabled(true);
                }
            }
        }.execute();
    }

    private static void setMessengerControlsEnabled(boolean enabled) {
        if (messengerLoginButton != null) {
            messengerLoginButton.setEnabled(enabled);
        }
        boolean loggedIn = isMessengerConnected() && !messengerPassport.isEmpty();
        if (establishSessionButton != null) {
            establishSessionButton.setEnabled(enabled && loggedIn);
        }
        if (loadOfflineButton != null) {
            loadOfflineButton.setEnabled(enabled && loggedIn);
        }
        if (sendMessageButton != null) {
            sendMessageButton.setEnabled(enabled && loggedIn);
        }
    }

    private static void appendMessengerLine(String text) {
        SwingUtilities.invokeLater(() -> {
            if (messengerHistoryArea == null) {
                return;
            }
            if (!messengerHistoryArea.getText().isEmpty()) {
                messengerHistoryArea.append("\n\n");
            }
            messengerHistoryArea.append(text);
            messengerHistoryArea.setCaretPosition(messengerHistoryArea.getDocument().getLength());
        });
    }

    private static void showMessengerError(String text) {
        JOptionPane.showMessageDialog(messengerFrame, text, "Мессенджер", JOptionPane.ERROR_MESSAGE);
    }

    private static String messengerProtocolValue(String value, String fieldName) {
        String result = value == null ? "" : value.trim().replaceAll("[\\r\\n]+", " ");
        if (result.contains("|||")) {
            throw new IllegalArgumentException("Поле «" + fieldName + "» не может содержать последовательность |||.");
        }
        return result;
    }

    private static final Object MESSENGER_IO_LOCK = new Object();

    private static boolean isMessengerConnected() {
        return messengerSocket != null && messengerSocket.isConnected() && !messengerSocket.isClosed();
    }

    private static void ensureMessengerConnected() throws IOException {
        if (!isMessengerConnected()) {
            throw new IOException("Нет соединения с сервером мессенджера.");
        }
    }

    private static synchronized void closeMessengerConnection() {
        if (messengerSocket != null) {
            try {
                messengerSocket.close();
            } catch (IOException ignored) {
                // Соединение уже закрыто.
            }
        }
        messengerSocket = null;
        messengerReader = null;
        messengerWriter = null;
        messengerListener = null;
        messengerResponses.clear();
        messengerPassport = "";
        messengerEmail = "";
    }

    // Хранилище приватных ключей и Signal-сессий находится только в client/messenger_data.db.
    private static void openMessengerStore() throws Exception {
        synchronized (MESSENGER_DB_LOCK) {
            if (messengerDb != null && !messengerDb.isClosed()) {
                return;
            }
            Class.forName("org.sqlite.JDBC");
            Path workingDirectory = Path.of("").toAbsolutePath().normalize();
            Path directory = workingDirectory.getFileName() != null
                    && "client".equals(workingDirectory.getFileName().toString())
                            ? workingDirectory
                            : workingDirectory.resolve("client");
            Files.createDirectories(directory);
            messengerDb = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("messenger_data.db"));
            try (Statement statement = messengerDb.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS UsersAndKeys (
                            pasport_number TEXT PRIMARY KEY,
                            Ed25519_private BLOB NOT NULL,
                            X25519_private BLOB NOT NULL,
                            Ed25519_public BLOB NOT NULL,
                            X25519_public BLOB NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS SignalSessions (
                            my_pasport_number TEXT NOT NULL,
                            peer_email TEXT NOT NULL,
                            session_data BLOB NOT NULL,
                            PRIMARY KEY (my_pasport_number, peer_email)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS private_pre_keys (
                            my_pasport_number TEXT NOT NULL,
                            type_of_key TEXT NOT NULL,
                            key_id INTEGER NOT NULL,
                            key BLOB NOT NULL,
                            PRIMARY KEY (my_pasport_number, type_of_key, key_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS TrustedIdentities (
                            my_pasport_number TEXT NOT NULL,
                            peer_email TEXT NOT NULL,
                            identity_public BLOB NOT NULL,
                            PRIMARY KEY (my_pasport_number, peer_email)
                        )
                        """);
            }
        }
    }

    private static Connection requireMessengerDb() {
        if (messengerDb == null) {
            throw new IllegalStateException("Локальное хранилище мессенджера не открыто.");
        }
        return messengerDb;
    }

    private static boolean hasMessengerLocalKeys(String passport) {
        return getMessengerIdentityPrivate(passport) != null
                && getMessengerIdentityPublic(passport) != null
                && getMessengerPreKey(passport, "SIGNED", 1) != null;
    }

    private static void resetMessengerLocalKeys(String passport) throws SQLException {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement sessions = requireMessengerDb().prepareStatement(
                    "DELETE FROM SignalSessions WHERE my_pasport_number = ?");
                 PreparedStatement preKeys = requireMessengerDb().prepareStatement(
                    "DELETE FROM private_pre_keys WHERE my_pasport_number = ?");
                 PreparedStatement identities = requireMessengerDb().prepareStatement(
                    "DELETE FROM UsersAndKeys WHERE pasport_number = ?")) {
                sessions.setString(1, passport);
                preKeys.setString(1, passport);
                identities.setString(1, passport);
                sessions.executeUpdate();
                preKeys.executeUpdate();
                identities.executeUpdate();
            }
        }
    }

    private static void saveMessengerPrivateKeys(String passport, byte[] identityPrivate, byte[] x25519Private,
            byte[] identityPublic, byte[] x25519Public) throws SQLException {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement statement = requireMessengerDb().prepareStatement("""
                    INSERT INTO UsersAndKeys (pasport_number, Ed25519_private, X25519_private, Ed25519_public, X25519_public)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(pasport_number) DO UPDATE SET
                        Ed25519_private = excluded.Ed25519_private,
                        X25519_private = excluded.X25519_private,
                        Ed25519_public = excluded.Ed25519_public,
                        X25519_public = excluded.X25519_public
                    """)) {
                statement.setString(1, passport);
                statement.setBytes(2, identityPrivate);
                statement.setBytes(3, x25519Private);
                statement.setBytes(4, identityPublic);
                statement.setBytes(5, x25519Public);
                statement.executeUpdate();
            }
        }
    }

    private static byte[] getMessengerIdentityPrivate(String passport) {
        return getMessengerSingleBlob("SELECT Ed25519_private FROM UsersAndKeys WHERE pasport_number = ?", passport);
    }

    private static byte[] getMessengerIdentityPublic(String passport) {
        return getMessengerSingleBlob("SELECT Ed25519_public FROM UsersAndKeys WHERE pasport_number = ?", passport);
    }

    private static void saveMessengerPreKey(String passport, String type, int id, byte[] key) throws SQLException {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement statement = requireMessengerDb().prepareStatement("""
                    INSERT OR REPLACE INTO private_pre_keys (my_pasport_number, type_of_key, key_id, key)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setString(1, passport);
                statement.setString(2, type);
                statement.setInt(3, id);
                statement.setBytes(4, key);
                statement.executeUpdate();
            }
        }
    }

    private static byte[] getMessengerPreKey(String passport, String type, int id) {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement statement = requireMessengerDb().prepareStatement("""
                    SELECT key FROM private_pre_keys
                    WHERE my_pasport_number = ? AND type_of_key = ? AND key_id = ?
                    """)) {
                statement.setString(1, passport);
                statement.setString(2, type);
                statement.setInt(3, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getBytes(1) : null;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось прочитать приватный ключ.", exception);
            }
        }
    }

    private static void deleteMessengerPreKey(String passport, String type, int id) {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement statement = requireMessengerDb().prepareStatement("""
                    DELETE FROM private_pre_keys
                    WHERE my_pasport_number = ? AND type_of_key = ? AND key_id = ?
                    """)) {
                statement.setString(1, passport);
                statement.setString(2, type);
                statement.setInt(3, id);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось удалить использованный pre-key.", exception);
            }
        }
    }

    private static void saveMessengerSession(String passport, String peerEmail, byte[] session) {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement statement = requireMessengerDb().prepareStatement("""
                    INSERT OR REPLACE INTO SignalSessions (my_pasport_number, peer_email, session_data)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, passport);
                statement.setString(2, peerEmail);
                statement.setBytes(3, session);
                statement.executeUpdate();
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось сохранить Signal-сессию.", exception);
            }
        }
    }

    private static byte[] getMessengerSession(String passport, String peerEmail) {
        return getMessengerSingleBlob("""
                SELECT session_data FROM SignalSessions
                WHERE my_pasport_number = ? AND peer_email = ?
                """, passport, peerEmail);
    }

    private static boolean ensureMessengerTrustedIdentity(String passport, String peerEmail, byte[] identity) {
        synchronized (MESSENGER_DB_LOCK) {
            byte[] saved = getMessengerSingleBlob("""
                    SELECT identity_public FROM TrustedIdentities
                    WHERE my_pasport_number = ? AND peer_email = ?
                    """, passport, peerEmail);
            if (saved != null) {
                return Arrays.equals(saved, identity);
            }
            try (PreparedStatement statement = requireMessengerDb().prepareStatement("""
                    INSERT INTO TrustedIdentities (my_pasport_number, peer_email, identity_public)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, passport);
                statement.setString(2, peerEmail);
                statement.setBytes(3, identity);
                statement.executeUpdate();
                return true;
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось сохранить ключ доверия собеседника.", exception);
            }
        }
    }

    private static byte[] getMessengerTrustedIdentity(String passport, String peerEmail) {
        return getMessengerSingleBlob("""
                SELECT identity_public FROM TrustedIdentities
                WHERE my_pasport_number = ? AND peer_email = ?
                """, passport, peerEmail);
    }

    private static byte[] getMessengerSingleBlob(String sql, String... values) {
        synchronized (MESSENGER_DB_LOCK) {
            try (PreparedStatement statement = requireMessengerDb().prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) {
                    statement.setString(i + 1, values[i]);
                }
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getBytes(1) : null;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось прочитать локальное хранилище мессенджера.", exception);
            }
        }
    }

    /** Адаптер SignalProtocolStore, встроенный в единственный класс клиента. */
    private static final class MessengerStore implements SignalProtocolStore {
        private final String passport;

        private MessengerStore(String passport) {
            this.passport = passport;
        }

        @Override
        public IdentityKeyPair getIdentityKeyPair() {
            try {
                byte[] publicKey = getMessengerIdentityPublic(passport);
                byte[] privateKey = getMessengerIdentityPrivate(passport);
                if (publicKey == null || privateKey == null) {
                    throw new IllegalStateException("Локальная пара ключей идентификации не найдена.");
                }
                return new IdentityKeyPair(new IdentityKey(Curve.decodePoint(publicKey, 0)),
                        Curve.decodePrivatePoint(privateKey));
            } catch (Exception exception) {
                throw new IllegalStateException("Не удалось восстановить ключ идентификации.", exception);
            }
        }

        @Override
        public int getLocalRegistrationId() {
            return 12345;
        }

        @Override
        public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
            return ensureMessengerTrustedIdentity(passport, address.getName(), identityKey.getPublicKey().serialize());
        }

        @Override
        public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
            byte[] saved = getMessengerTrustedIdentity(passport, address.getName());
            return saved == null || Arrays.equals(saved, identityKey.getPublicKey().serialize());
        }

        @Override
        public IdentityKey getIdentity(SignalProtocolAddress address) {
            byte[] saved = getMessengerTrustedIdentity(passport, address.getName());
            try {
                return saved == null ? null : new IdentityKey(saved, 0);
            } catch (Exception exception) {
                throw new IllegalStateException("Сохранённый ключ собеседника повреждён.", exception);
            }
        }

        @Override
        public PreKeyRecord loadPreKey(int preKeyId) {
            byte[] key = getMessengerPreKey(passport, "ONE_TIME", preKeyId);
            try {
                return key == null ? null : new PreKeyRecord(key);
            } catch (IOException exception) {
                throw new IllegalStateException("Одноразовый ключ повреждён.", exception);
            }
        }

        @Override
        public void storePreKey(int preKeyId, PreKeyRecord record) {
            try {
                saveMessengerPreKey(passport, "ONE_TIME", preKeyId, record.serialize());
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось сохранить одноразовый ключ.", exception);
            }
        }

        @Override
        public boolean containsPreKey(int preKeyId) {
            return getMessengerPreKey(passport, "ONE_TIME", preKeyId) != null;
        }

        @Override
        public void removePreKey(int preKeyId) {
            deleteMessengerPreKey(passport, "ONE_TIME", preKeyId);
        }

        @Override
        public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) {
            byte[] key = getMessengerPreKey(passport, "SIGNED", signedPreKeyId);
            try {
                return key == null ? null : new SignedPreKeyRecord(key);
            } catch (IOException exception) {
                throw new IllegalStateException("Подписанный pre-key повреждён.", exception);
            }
        }

        @Override
        public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
            try {
                saveMessengerPreKey(passport, "SIGNED", signedPreKeyId, record.serialize());
            } catch (SQLException exception) {
                throw new IllegalStateException("Не удалось сохранить подписанный pre-key.", exception);
            }
        }

        @Override
        public boolean containsSignedPreKey(int signedPreKeyId) {
            return getMessengerPreKey(passport, "SIGNED", signedPreKeyId) != null;
        }

        @Override
        public void removeSignedPreKey(int signedPreKeyId) {
            deleteMessengerPreKey(passport, "SIGNED", signedPreKeyId);
        }

        @Override
        public List<SignedPreKeyRecord> loadSignedPreKeys() {
            return Collections.emptyList();
        }

        @Override
        public SessionRecord loadSession(SignalProtocolAddress address) {
            byte[] session = getMessengerSession(passport, address.getName());
            try {
                return session == null ? new SessionRecord() : new SessionRecord(session);
            } catch (IOException exception) {
                throw new IllegalStateException("Signal-сессия повреждена.", exception);
            }
        }

        @Override
        public void storeSession(SignalProtocolAddress address, SessionRecord record) {
            saveMessengerSession(passport, address.getName(), record.serialize());
        }

        @Override
        public boolean containsSession(SignalProtocolAddress address) {
            return getMessengerSession(passport, address.getName()) != null;
        }

        @Override
        public void deleteSession(SignalProtocolAddress address) {
            // Сессии не удаляются автоматически: это сохраняет эффект ratchet между запусками.
        }

        @Override
        public void deleteAllSessions(String name) {
            // Удаление сессий не требуется для текущего протокола интерфейса.
        }

        @Override
        public List<Integer> getSubDeviceSessions(String name) {
            return Collections.emptyList();
        }
    }

    private static String withoutPrefix(String value, String prefix) {
        String result = value == null ? "" : value.trim();
        return result.regionMatches(true, 0, prefix, 0, prefix.length())
                ? result.substring(prefix.length()).trim()
                : result;
    }

    private static void appendResultSection(StringBuilder output, String title, String value) {
        if (!output.isEmpty()) {
            output.append("\n\n");
        }
        output.append(title).append('\n');
        output.append("────────────────────────").append('\n');
        output.append(value);
    }

    private static void send(String message) throws IOException {
        synchronized (BioClient.class) {
            ensureConnected();
            writer.write(message);
            writer.write('\n');
            writer.flush();
        }
    }

    private static String readLine(int timeoutMs) throws IOException {
        synchronized (BioClient.class) {
            ensureConnected();
            socket.setSoTimeout(timeoutMs);
            try {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Сервер закрыл соединение.");
                }
                return line;
            } finally {
                socket.setSoTimeout(0);
            }
        }
    }

    private static String readOptionalLine(int timeoutMs) throws IOException {
        try {
            return readLine(timeoutMs);
        } catch (SocketTimeoutException timeout) {
            return null;
        }
    }

    /**
     * Сервер отправляет ответ модели одним write, но разрезает его на строки.
     * Формат не содержит маркера конца сообщения, поэтому после первой строки
     * дочитываем все немедленно доступные строки и ждём короткий тайм-аут.
     */
    private static String readMultilineReply(int firstLineTimeoutMs) throws IOException {
        StringBuilder result = new StringBuilder(readLine(firstLineTimeoutMs));
        synchronized (BioClient.class) {
            ensureConnected();
            socket.setSoTimeout(500);
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    result.append('\n').append(line);
                }
            } catch (SocketTimeoutException ignored) {
                // Это штатный признак окончания единственного ответа модели.
            } finally {
                socket.setSoTimeout(0);
            }
        }
        return result.toString();
    }

    private static void ensureConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("Нет соединения с сервером.");
        }
    }

    private static boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private static synchronized void closeConnection() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Соединение уже закрыто.
            }
        }
        socket = null;
        reader = null;
        writer = null;
    }

    private static void runAsync(Callable<String> task, Consumer<String> onSuccess, String errorPrefix) {
        setControlsEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    showError(errorPrefix + ": " + cause.getMessage());
                    connectionStatus.setText("Ошибка соединения.");
                } finally {
                    if (analysisInProgress) {
                        analysisInProgress = false;
                        backToPatientButton.setEnabled(true);
                    }
                    // onSuccess может изменить patientReady, поэтому доступность
                    // кнопок вычисляется только после обработки ответа сервера.
                    setControlsEnabled(true);
                }
            }
        }.execute();
    }

    private static void setControlsEnabled(boolean enabled) {
        if (connectButton != null) {
            connectButton.setEnabled(enabled);
        }
        if (findPatientButton != null) {
            findPatientButton.setEnabled(enabled);
        }
        if (registerButton != null) {
            registerButton.setEnabled(enabled && !patientReady && omsField.isEditable());
        }
        if (toAnalysisButton != null) {
            toAnalysisButton.setEnabled(enabled && patientReady);
        }
        if (analyzeButton != null) {
            analyzeButton.setEnabled(enabled && patientReady);
        }
        if (saveCardButton != null) {
            saveCardButton.setEnabled(enabled && diseaseNameField.isEnabled());
        }
    }

    private static void setProfileEditable(boolean editable) {
        omsField.setEditable(editable);
        fioField.setEditable(editable);
        emailField.setEditable(editable);
        birthDateField.setEditable(editable);
        genderBox.setEnabled(editable);
        allergiesField.setEditable(editable);
        chronicDiseasesField.setEditable(editable);
        emergencyContactField.setEditable(editable);
        registerButton.setEnabled(editable && !patientReady);
        toAnalysisButton.setEnabled(patientReady);
    }

    private static void setCardFieldsEnabled(boolean enabled) {
        diseaseNameField.setEnabled(enabled);
        diseaseDescriptionArea.setEnabled(enabled);
        diseaseNoteArea.setEnabled(enabled);
        importantBox.setEnabled(enabled);
        saveCardButton.setEnabled(enabled);
    }

    private static void clearPatientForm() {
        snilsField.setText("");
        clearProfileFields();
        complaintArea.setText("");
        resultArea.setText("");
        clearVisitCardFields();
    }

    private static void clearVisitCardFields() {
        diseaseNameField.setText("");
        diseaseDescriptionArea.setText("");
        diseaseNoteArea.setText("");
        importantBox.setSelected(false);
        setCardFieldsEnabled(false);
    }

    private static void clearProfileFields() {
        omsField.setText("");
        fioField.setText("");
        emailField.setText("");
        birthDateField.setText("");
        genderBox.setSelectedItem("Не указан");
        allergiesField.setText("");
        chronicDiseasesField.setText("");
        emergencyContactField.setText("");
        toAnalysisButton.setEnabled(false);
    }

    private static String normalizeSnils(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() != 11) {
            throw new IllegalArgumentException("СНИЛС должен содержать 11 цифр.");
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6, 9)
                + " " + digits.substring(9, 11);
    }

    private static String protocolValue(String value, String fieldName) {
        String result = value == null ? "" : value.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (result.contains("|||")) {
            throw new IllegalArgumentException("Поле «" + fieldName + "» не может содержать последовательность |||.");
        }
        return result;
    }

    private static void addLabelAndField(JPanel panel, String label, java.awt.Component field, int row) {
        GridBagConstraints labelConstraints = constraints(0, row);
        labelConstraints.anchor = GridBagConstraints.EAST;
        panel.add(new JLabel(label + ":"), labelConstraints);
        GridBagConstraints fieldConstraints = constraints(1, row);
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fieldConstraints);
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(5, 5, 5, 5);
        return c;
    }

    private static void showError(String text) {
        JOptionPane.showMessageDialog(frame, text, "BIO-клиент", JOptionPane.ERROR_MESSAGE);
    }

}
