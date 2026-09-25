package com.relaydoodle.client;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientMain extends Application {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected = false;

    private String username;
    private String role = "NONE";
    private String currentDrawer = "";
    private String currentGuesser = "";

    private Canvas canvas;
    private GraphicsContext gc;

    private TextArea chatArea;
    private TextField chatInput;
    private TextField guessInput;
    private TextField usernameInput;

    private TextField serverHostInput;
    private TextField serverPortInput;

    private ProgressBar turnTimer;
    private Label statusBanner;

    private ListView<String> playerList;
    private final ObservableList<String> players = FXCollections.observableArrayList();

    private Label guesserLabel;
    private Label drawersLabel;

    private TextArea scoreboardArea;

    private double brushSize = 4;
    private boolean eraserMode = false;

    private final ExecutorService listenerExec = Executors.newSingleThreadExecutor();
    private Timeline timerTimeline;

    @Override
    public void start(Stage stage) {
        stage.setTitle("RelayDoodle");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setResizable(true);

        canvas = new Canvas(900, 550);
        gc = canvas.getGraphicsContext2D();
        gc.setLineWidth(brushSize);
        clearCanvas();

        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> handleLocalDraw(e.getX(), e.getY()));
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> handleLocalDraw(e.getX(), e.getY()));

        StackPane canvasPane = new StackPane(canvas);
        canvasPane.setPadding(new Insets(10));
        canvasPane.setMinWidth(400);
        canvasPane.setMinHeight(350);
        canvasPane.setPrefHeight(550);

        canvas.widthProperty().bind(canvasPane.widthProperty().subtract(20));
        canvas.heightProperty().bind(canvasPane.heightProperty().subtract(20));
        canvas.widthProperty().addListener((obs, oldW, newW) -> clearCanvas());
        canvas.heightProperty().addListener((obs, oldH, newH) -> clearCanvas());

        playerList = new ListView<>(players);
        playerList.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        playerList.setPrefHeight(40);
        playerList.setMaxHeight(40);

        chatArea = new TextArea();
        chatArea.setWrapText(true);
        chatArea.setEditable(false);
        chatArea.setPrefHeight(200);
        chatArea.setMaxHeight(200);

        chatInput = new TextField();
        Button chatBtn = new Button("Send");
        chatBtn.setOnAction(e -> sendChat());

        HBox chatInputHBox = new HBox(8, chatInput, chatBtn);
        chatInputHBox.setPadding(new Insets(6));
        HBox.setHgrow(chatInput, Priority.ALWAYS);

        VBox chatBox = new VBox(new Label("Chat"), chatArea, chatInputHBox);
        chatBox.setSpacing(8);
        chatBox.setPadding(new Insets(10));
        chatBox.setPrefWidth(320);

        guesserLabel = new Label("Guesser: -");
        drawersLabel = new Label("Drawers: -");

        VBox roundInfoBox = new VBox(
                new Label("Round Info"),
                guesserLabel,
                drawersLabel
        );
        roundInfoBox.setSpacing(4);
        roundInfoBox.setPadding(new Insets(10));
        roundInfoBox.setMinHeight(80);
        roundInfoBox.setPrefHeight(80);

        VBox rightColumn = new VBox(roundInfoBox, chatBox);
        rightColumn.setSpacing(10);
        rightColumn.setPadding(new Insets(10));
        rightColumn.setPrefWidth(350);
        rightColumn.setMinWidth(350);

        statusBanner = new Label("Welcome to RelayDoodle!");
        statusBanner.setAlignment(Pos.CENTER);
        statusBanner.setMaxWidth(Double.MAX_VALUE);
        statusBanner.setPadding(new Insets(10));
        statusBanner.setStyle(
                "-fx-font-size: 18px; -fx-font-weight: bold; " +
                        "-fx-background-color: #4a148c; -fx-text-fill: white;"
        );

        scoreboardArea = new TextArea();
        scoreboardArea.setEditable(false);
        scoreboardArea.setPrefRowCount(6);
        scoreboardArea.setStyle(
                "-fx-font-size: 14px; -fx-font-weight: bold; " +
                        "-fx-background-color: #f5f5f5; -fx-border-color: #4a148c; " +
                        "-fx-border-width: 2px;"
        );

        Label scoreboardTitle = new Label("🏆 SCOREBOARD");
        scoreboardTitle.setStyle(
                "-fx-font-size: 16px; -fx-font-weight: bold; " +
                        "-fx-text-fill: #4a148c;"
        );
        scoreboardTitle.setMaxWidth(Double.MAX_VALUE);
        scoreboardTitle.setAlignment(Pos.CENTER);

        VBox scoreboardBox = new VBox(6, scoreboardTitle, scoreboardArea);
        scoreboardBox.setPadding(new Insets(10));
        scoreboardBox.setMaxWidth(250);
        scoreboardBox.setStyle(
                "-fx-background-color: white; -fx-border-color: #4a148c; " +
                        "-fx-border-width: 2px; -fx-border-radius: 5px; " +
                        "-fx-background-radius: 5px;"
        );

        HBox topBar = new HBox();
        topBar.getChildren().addAll(statusBanner, scoreboardBox);
        HBox.setHgrow(statusBanner, Priority.ALWAYS);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setSpacing(10);
        topBar.setPadding(new Insets(0, 10, 0, 0));

        // players are shwon here
        HBox playerBar = new HBox(10);
        playerBar.setPadding(new Insets(5, 10, 5, 10));
        playerBar.setAlignment(Pos.CENTER_LEFT);
        Label playersLabel = new Label("Players:");
        playersLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        playerBar.getChildren().addAll(playersLabel, playerList);
        HBox.setHgrow(playerList, Priority.ALWAYS);
        playerBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");

        VBox topSection = new VBox(topBar, playerBar);
        topSection.setSpacing(0);

        turnTimer = new ProgressBar(0);
        turnTimer.setPrefWidth(300);
        turnTimer.setMaxWidth(Double.MAX_VALUE);

        guessInput = new TextField();
        guessInput.setPromptText("Enter your guess...");
        Button guessBtn = new Button("Submit Guess");
        guessBtn.setOnAction(e -> sendGuess());

        HBox guessBox = new HBox(8, guessInput, guessBtn);
        HBox.setHgrow(guessInput, Priority.ALWAYS);
        guessBox.setAlignment(Pos.CENTER_LEFT);

        serverHostInput = new TextField();
        serverHostInput.setPromptText("Server host");
        serverHostInput.setText("localhost");
        serverHostInput.setPrefWidth(150);

        serverPortInput = new TextField("5000");
        serverPortInput.setPromptText("Port");
        serverPortInput.setPrefWidth(60);

        usernameInput = new TextField();
        usernameInput.setPromptText("Username");
        usernameInput.setPrefWidth(120);

        Button connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> connectToServer());

        HBox connectBox = new HBox(8, new Label("Host:"), serverHostInput, new Label("Port:"), serverPortInput, new Label("User:"), usernameInput, connectBtn);
        connectBox.setAlignment(Pos.CENTER_LEFT);

        Slider sizeSlider = new Slider(2, 20, brushSize);
        sizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> brushSize = newVal.doubleValue());

        Button penBtn = new Button("Pen");
        penBtn.setOnAction(e -> eraserMode = false);

        Button eraserBtn = new Button("Eraser");
        eraserBtn.setOnAction(e -> eraserMode = true);

        VBox toolBox = new VBox(
                new Label("Tools"),
                penBtn,
                eraserBtn,
                new Label("Brush Size"), sizeSlider
        );
        toolBox.setSpacing(6);
        toolBox.setPadding(new Insets(8));
        toolBox.setMaxWidth(300);
        toolBox.setMaxHeight(120);

        // bottom panel which shows timer, guess, connect, and tools
        HBox bottomPanel = new HBox(12);
        bottomPanel.setPadding(new Insets(8));
        bottomPanel.setAlignment(Pos.CENTER_LEFT);

        VBox leftBottomGroup = new VBox(6, turnTimer, guessBox, connectBox);
        leftBottomGroup.setAlignment(Pos.CENTER_LEFT);
        leftBottomGroup.setMinWidth(300);

        HBox.setHgrow(leftBottomGroup, Priority.ALWAYS);
        bottomPanel.getChildren().addAll(leftBottomGroup, toolBox);

        ScrollPane rightScroll = new ScrollPane(rightColumn);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        rightScroll.setMinWidth(370);
        rightScroll.setPrefWidth(370);

        BorderPane root = new BorderPane();
        root.setTop(topSection);
        root.setCenter(canvasPane);
        root.setRight(rightScroll);
        root.setBottom(bottomPanel);

        Scene scene = new Scene(root, 1450, 850);
        stage.setScene(scene);
        stage.show();
    }

    // code logic for client connecting to server
    private void connectToServer() {
        if (connected) {
            appendChat("Already connected.");
            return;
        }

        String host = serverHostInput.getText().trim();
        if (host.isEmpty()) {
            appendChat("Enter server host first.");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(serverPortInput.getText().trim());
        } catch (NumberFormatException e) {
            appendChat("Invalid port number.");
            return;
        }

        String name = usernameInput.getText().trim();
        if (name.isEmpty()) {
            appendChat("Enter username first.");
            return;
        }

        username = name;

        try {
            socket = new Socket(host, port);
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

            connected = true;
            out.println("JOIN " + username);

            appendChat("Connected to " + host + ":" + port + " as: " + username);
            startListening();

        } catch (Exception e) {
            appendChat("Connection failed: " + e.getMessage());
        }
    }

    private void startListening() {
        listenerExec.submit(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String msg = line;
                    Platform.runLater(() -> handleServerMessage(msg));
                }
            } catch (Exception ignored) {
                Platform.runLater(() -> appendChat("Disconnected from server."));
            }
        });
    }

    // server send these messages
    private void handleServerMessage(String msg) {

        if (msg.startsWith("CHAT ")) {
            appendChat(msg.substring(5));
        }

        else if (msg.startsWith("ROLE ")) {
            role = msg.substring(5);
            statusBanner.setText("You are: " + role);
        }

        else if (msg.startsWith("PROMPT ")) {
            appendChat("Prompt: " + msg.substring(7));
        }

        else if (msg.startsWith("ROUND_ROLES ")) {
            String[] p = msg.split(" ");
            if (p.length >= 2) {
                currentGuesser = p[1];
                guesserLabel.setText("Guesser: " + currentGuesser);
            }

            if (p.length > 2) {
                StringBuilder dr = new StringBuilder();
                for (int i = 2; i < p.length; i++) {
                    dr.append(p[i]);
                    if (i < p.length - 1) dr.append(", ");
                }
                drawersLabel.setText("Drawers: " + dr);
            } else {
                drawersLabel.setText("Drawers: -");
            }
        }

        else if (msg.startsWith("TURN_DRAW ")) {
            String[] p = msg.split(" ");
            currentDrawer = p[1];
            startTimer(Integer.parseInt(p[2]));

            statusBanner.setText(
                    currentDrawer.equals(username)
                            ? "Your turn to DRAW!"
                            : currentDrawer + " is drawing..."
            );
        }

        else if (msg.startsWith("TURN_GUESS ")) {
            String[] p = msg.split(" ");
            currentGuesser = p[1];
            startTimer(Integer.parseInt(p[2]));

            statusBanner.setText(
                    currentGuesser.equals(username)
                            ? "Your turn to GUESS!"
                            : currentGuesser + " is guessing..."
            );
        }

        else if (msg.startsWith("DRAW ")) {
            handleRemoteDraw(msg);
        }

        else if (msg.startsWith("ERASE ")) {
            handleRemoteErase(msg);
        }

        else if (msg.startsWith("PLAYER_LIST ")) {
            players.clear();
            String[] arr = msg.substring(12).split(",");
            for (String s : arr) {
                if (!s.isBlank()) players.add(s.trim());
            }
        }

        else if (msg.startsWith("ROUND_OVER ") || msg.startsWith("ROUND_TIMEOUT ")) {
            clearCanvas();
            stopTimer();
        }

        else if (msg.startsWith("SCORES ")) {
            String payload = msg.substring(7);
            appendChat("Scores: " + payload);
            updateScoreboard(payload);
        }

        else {
            appendChat("SERVER: " + msg);
        }
    }

    // erasing drawing where we simply colour tha page white
    private void handleLocalDraw(double x, double y) {
        if (!connected || !username.equals(currentDrawer)) return;

        if (eraserMode) {
            gc.setFill(Color.web("#FFFFFF"));
            gc.fillRect(x - brushSize / 2, y - brushSize / 2, brushSize, brushSize);
            out.println("ERASE " + x + " " + y + " " + brushSize);
            return;
        }

        gc.setFill(Color.BLACK);
        gc.fillOval(x - brushSize / 2, y - brushSize / 2, brushSize, brushSize);

        out.println("DRAW " + x + " " + y);
    }

    private void handleRemoteDraw(String msg) {
        String[] p = msg.split(" ");
        double x = Double.parseDouble(p[2]);
        double y = Double.parseDouble(p[3]);

        gc.setFill(Color.BLACK);
        gc.fillOval(x - brushSize / 2, y - brushSize / 2, brushSize, brushSize);
    }

    private void handleRemoteErase(String msg) {
        String[] p = msg.split(" ");
        double x = Double.parseDouble(p[2]);
        double y = Double.parseDouble(p[3]);
        double size = Double.parseDouble(p[4]);

        gc.setFill(Color.web("#FFFFFF"));
        gc.fillRect(x - size / 2, y - size / 2, size, size);
    }

    // guessing part client side
    private void sendChat() {
        if (!connected) return;
        String txt = chatInput.getText().trim();
        if (!txt.isEmpty()) {
            out.println("CHAT " + txt);
            chatInput.clear();
        }
    }

    private void sendGuess() {
        if (!connected) return;

        if (!username.equals(currentGuesser)) {
            appendChat("You are not the guesser!");
            return;
        }

        String g = guessInput.getText().trim();
        if (!g.isEmpty()) {
            out.println("GUESS " + g);
            guessInput.clear();
        }
    }

    // progress and timer bar for turns
    private void startTimer(int sec) {
        stopTimer();
        turnTimer.setProgress(1);

        timerTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(turnTimer.progressProperty(), 1)),
                new KeyFrame(Duration.seconds(sec), new KeyValue(turnTimer.progressProperty(), 0))
        );
        timerTimeline.play();
    }

    private void stopTimer() {
        if (timerTimeline != null) timerTimeline.stop();
        turnTimer.setProgress(0);
    }

    // clear the whiteboard
    private void clearCanvas() {
        if (gc == null || canvas == null) return;
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setLineWidth(brushSize);
    }

    // scoreboard
    private void updateScoreboard(String scoresText) {
        scoreboardArea.clear();
        String[] entries = scoresText.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            sb.append(entry.replace("=", ": ")).append("\n");
        }
        scoreboardArea.setText(sb.toString());
    }

    private void appendChat(String msg) {
        chatArea.appendText(msg + "\n");
    }

    @Override
    public void stop() throws Exception {
        listenerExec.shutdownNow();
        if (socket != null) socket.close();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
