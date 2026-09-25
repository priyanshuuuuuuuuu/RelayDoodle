package com.relaydoodle.server;

import java.io.*;
import java.net.*;

public class ClientHandler extends Thread {

    private final Socket socket;
    private final ServerMain server;

    private PrintWriter out;
    private BufferedReader in;

    private String username;

    public ClientHandler(Socket socket, ServerMain server) {
        this.socket = socket;
        this.server = server;
        setName("client-" + socket.getRemoteSocketAddress());
    }

    public void sendLine(String msg) {
        if (out != null) {
            out.println(msg);
            out.flush();
        }
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));

            sendLine("WELCOME RelayDoodle Server Ready. Use: JOIN <username>");

            String line;
            while ((line = in.readLine()) != null) {

                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" ", 2);
                String cmd  = parts[0].toUpperCase();
                String rest = (parts.length > 1) ? parts[1] : "";

                switch (cmd) {

                    case "JOIN":
                        handleJoin(rest);
                        break;

                    case "DRAW":
                        handleDraw(rest);
                        break;

                    case "ERASE":
                        handleErase(rest);
                        break;

                    case "GUESS":
                        handleGuess(rest);
                        break;

                    case "CHAT":
                        handleChat(rest);
                        break;

                    default:
                        sendLine("ERROR Unknown command: " + cmd);
                }
            }
        } catch (IOException ignored) {
        } finally {
            cleanup();
        }
    }

    private void handleJoin(String rest) {
        String name = rest.trim();

        if (name.isEmpty()) {
            sendLine("ERROR Username cannot be empty.");
            return;
        }

        username = name;
        server.registerClient(username, this);
        sendLine("CHAT server Welcome " + username);
    }


    // drawing logic
    private void handleDraw(String rest) {
        if (!isJoined()) return;

        String[] p = rest.split(" ");
        if (p.length != 2) {
            sendLine("ERROR Invalid DRAW format. Use: DRAW <x> <y>");
            return;
        }

        server.handleDraw(username, p[0], p[1]);
    }


    private void handleErase(String rest) {
        if (!isJoined()) return;

        String[] p = rest.split(" ");
        if (p.length != 3) {
            sendLine("ERROR Invalid ERASE format. Use: ERASE <x> <y> <size>");
            return;
        }

        String x = p[0];
        String y = p[1];
        String size = p[2];

        server.handleErase(username, x, y, size);
    }


    private void handleGuess(String rest) {
        if (!isJoined()) return;

        String guess = rest.trim();
        if (guess.isEmpty()) {
            sendLine("ERROR Guess cannot be empty.");
            return;
        }

        server.handleGuess(username, guess);
    }


    private void handleChat(String rest) {
        if (!isJoined()) return;

        server.sendToAllClients("CHAT " + username + ": " + rest);
    }


    private boolean isJoined() {
        if (username == null) {
            sendLine("ERROR Join first using JOIN <name>.");
            return false;
        }
        return true;
    }

    private void cleanup() {
        try { socket.close(); } catch (IOException ignored) {}

        if (username != null) {
            server.unregisterClient(username);
        }

        System.out.println("[DISCONNECT] " + username + " left.");
    }
}
