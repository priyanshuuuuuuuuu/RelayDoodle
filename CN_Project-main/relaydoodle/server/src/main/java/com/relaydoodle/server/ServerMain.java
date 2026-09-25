package com.relaydoodle.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerMain {

    private static final int PORT = 5000;

    private final ServerSocket serverSocket;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final List<String> playerOrder = Collections.synchronizedList(new ArrayList<>());

    private final Map<String, Integer> scores = new ConcurrentHashMap<>();

    private volatile String secretWord = null;
    private volatile String guesser = null;
    private volatile List<String> drawers = new ArrayList<>();
    private volatile String currentDrawer = null;

    private volatile Phase phase = Phase.WAITING;
    private int guesserIndex = 0;
    private int drawerTurnIndex = 0;

    private final int DRAWER_TIME = 30;
    private final int GUESS_TIME = 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Random rand = new Random();

    // track scehduled tasks to cancel them
    private ScheduledFuture<?> drawerTimer = null;
    private ScheduledFuture<?> guessTimer = null;

    private final List<String> prompts = Arrays.asList(
            "apple", "car", "house", "tree",
            "cat", "dog", "bicycle", "pencil"
    );

    private int drawerTurnNumber = 0;

    private enum Phase { WAITING, DRAWING, GUESSING }

    // helps in remote connection of server with clients
    public ServerMain(int port) throws IOException {
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        System.out.println("SERVER READY — Listening on port " + port);
        System.out.println("Server bound to all network interfaces (0.0.0.0)");
        System.out.println("Local IP: " + InetAddress.getLocalHost().getHostAddress());
    }

    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    Socket s = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(s, this);
                    handler.start();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "accept-thread").start();
    }

    public synchronized void registerClient(String username, ClientHandler handler) {
        clients.put(username, handler);

        if (!playerOrder.contains(username))
            playerOrder.add(username);

        scores.putIfAbsent(username, 0);

        handler.sendLine("PLAYER_LIST " + numberedList());
        sendToAllClients("USER_JOINED " + username);
        sendToAllClients("PLAYER_LIST " + numberedList());

        System.out.println("[JOIN] " + username + " joined.");

        if (playerOrder.size() == 4 && phase == Phase.WAITING) {
            startRound();
        }
    }

    // when client leaves the game
    public synchronized void unregisterClient(String username) {
        clients.remove(username);
        playerOrder.remove(username);

        sendToAllClients("USER_LEFT " + username);
        sendToAllClients("PLAYER_LIST " + numberedList());

        System.out.println("[LEFT] " + username);

        if (drawerTimer != null) {
            drawerTimer.cancel(false);
            drawerTimer = null;
        }
        if (guessTimer != null) {
            guessTimer.cancel(false);
            guessTimer = null;
        }

        secretWord = null;
        phase = Phase.WAITING;
        currentDrawer = null;
        guesser = null;
        drawerTurnNumber = 0;
    }

    private String numberedList() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String p : playerOrder) {
            sb.append(i).append(".").append(p).append(",");
            i++;
        }
        return sb.toString();
    }

    private synchronized void startRound() {
        if (playerOrder.size() < 4) return;

        phase = Phase.DRAWING;
        secretWord = prompts.get(rand.nextInt(prompts.size()));

        guesser = playerOrder.get(guesserIndex % playerOrder.size());
        guesserIndex++;

        drawers = new ArrayList<>();
        for (String p : playerOrder) {
            if (!p.equals(guesser)) drawers.add(p);
        }

        drawerTurnIndex = 0;
        drawerTurnNumber = 0;

        System.out.println("Secret Word: " + secretWord);

        ClientHandler gh = clients.get(guesser);
        if (gh != null) gh.sendLine("ROLE GUESSER");

        for (String d : drawers) {
            ClientHandler ch = clients.get(d);
            if (ch != null) {
                ch.sendLine("ROLE DRAWER");
                ch.sendLine("PROMPT " + secretWord);
            }
        }

        broadcastRoundRoles();

        sendToAllClients("CHAT server New round started!");
        startNextDrawerTurn();
    }

    // shows players who is guesser and who are drawers
    private void broadcastRoundRoles() {
        StringBuilder sb = new StringBuilder("ROUND_ROLES ");
        sb.append(guesser).append(" ");
        for (String d : drawers) {
            sb.append(d).append(" ");
        }
        sendToAllClients(sb.toString().trim());
    }

    private synchronized void startNextDrawerTurn() {
        if (secretWord == null || phase != Phase.DRAWING) return;

        if (drawerTurnIndex >= drawers.size()) {
            startGuessPhase();
            return;
        }

        currentDrawer = drawers.get(drawerTurnIndex);
        drawerTurnIndex++;
        drawerTurnNumber = drawerTurnIndex;

        sendToAllClients("TURN_DRAW " + currentDrawer + " " + DRAWER_TIME);

        // Cancel any previous drawer timer
        if (drawerTimer != null) {
            drawerTimer.cancel(false);
        }

        drawerTimer = scheduler.schedule(() -> {
            synchronized (ServerMain.this) {
                if (phase == Phase.DRAWING && secretWord != null) {
                    startNextDrawerTurn();
                }
            }
        }, DRAWER_TIME, TimeUnit.SECONDS);
    }

    // seperate 60sec for guesser to guess correctly
    private synchronized void startGuessPhase() {
        phase = Phase.GUESSING;
        currentDrawer = null;
        sendToAllClients("TURN_GUESS " + guesser + " " + GUESS_TIME);

        if (drawerTimer != null) {
            drawerTimer.cancel(false);
            drawerTimer = null;
        }

        guessTimer = scheduler.schedule(() -> {
            synchronized (ServerMain.this) {
                if (secretWord != null) {
                    sendToAllClients("ROUND_TIMEOUT " + secretWord);
                    endRound();
                }
            }
        }, GUESS_TIME, TimeUnit.SECONDS);
    }


    private synchronized void endRound() {
        if (drawerTimer != null) {
            drawerTimer.cancel(false);
            drawerTimer = null;
        }
        if (guessTimer != null) {
            guessTimer.cancel(false);
            guessTimer = null;
        }

        phase = Phase.WAITING;
        secretWord = null;
        currentDrawer = null;
        guesser = null;
        drawerTurnNumber = 0;

        sendToAllClients("SCORES " + scoresToString());

        scheduler.schedule(() -> {
            synchronized (ServerMain.this) {
                if (playerOrder.size() == 4)
                    startRound();
            }
        }, 4, TimeUnit.SECONDS);
    }

    private String scoresToString() {
        StringBuilder sb = new StringBuilder();
        for (String p : playerOrder) {
            sb.append(p).append("=").append(scores.getOrDefault(p, 0)).append("  ");
        }
        return sb.toString();
    }

    // guessing part logic
    public synchronized void handleGuess(String user, String guess) {

        if (secretWord == null || (!phase.equals(Phase.DRAWING) && !phase.equals(Phase.GUESSING))) {
            clients.get(user).sendLine("ERROR: You are not in guessing window.");
            return;
        }

        if (!user.equals(guesser)) {
            clients.get(user).sendLine("ERROR: Only guesser may guess you are not guesser.");
            return;
        }

        if (guess.equalsIgnoreCase(secretWord)) {
            int points = computeGuessPoints();

            sendToAllClients("ROUND_OVER " + user + " " + secretWord + " " + points + "pts");
            scores.put(user, scores.getOrDefault(user, 0) + points);

            endRound();
        } else {
            sendToAllClients("CHAT server Wrong guess by " + user);
        }
    }

    private int computeGuessPoints() {
        switch (drawerTurnNumber) {
            case 1: return 3;
            case 2: return 2;
            case 3: return 1;
            default: return 1;
        }
    }

    // drawing on server side
    public synchronized void handleDraw(String user, String x, String y) {
        if (!phase.equals(Phase.DRAWING)) return;

        if (!user.equals(currentDrawer)) {
            clients.get(user).sendLine("ERROR Not your turn.");
            return;
        }

        sendToAllClients("DRAW " + user + " " + x + " " + y);
    }

    public synchronized void handleErase(String user, String x, String y, String size) {
        if (!phase.equals(Phase.DRAWING)) return;

        if (!user.equals(currentDrawer)) {
            ClientHandler ch = clients.get(user);
            if (ch != null) ch.sendLine("ERROR Not your turn.");
            return;
        }

        sendToAllClients("ERASE " + user + " " + x + " " + y + " " + size);
    }

    // send message to all clients
    public void sendToAllClients(String msg) {
        for (ClientHandler h : clients.values()) {
            h.sendLine(msg);
        }
    }

    public static void main(String[] args) throws Exception {
        ServerMain server = new ServerMain(PORT);
        server.start();

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Commands: players, scores, start, say <msg>, quit");

        while (true) {
            String line = br.readLine();
            if (line == null) continue;

            switch (line.toLowerCase()) {
                case "players":
                    System.out.println("Players: " + server.playerOrder);
                    break;
                case "scores":
                    System.out.println("Scores: " + server.scores);
                    break;
                case "start":
                    server.startRound();
                    break;
                case "quit":
                    System.exit(0);
                    break;
                default:
                    if (line.startsWith("say "))
                        server.sendToAllClients("CHAT server " + line.substring(4));
            }
        }
    }
}
