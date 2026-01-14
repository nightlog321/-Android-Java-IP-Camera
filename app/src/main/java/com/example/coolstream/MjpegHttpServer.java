package com.example.coolstream;


import android.util.Log;

import java.io.OutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal MJPEG HTTP server.
 *
 * - Serves multipart/x-mixed-replace JPEG frames obtained from FrameProvider.getFrame()
 * - Notifies ClientListener on connect/disconnect
 * - Tracks active clients and closes them on shutdown so stop is immediate
 *
 * Usage:
 *   MjpegHttpServer server = new MjpegHttpServer(8080, frameProvider);
 *   server.setClientListener(...);
 *   server.start();
 *   ...
 *   server.shutdown();
 */
public class MjpegHttpServer extends Thread {
    private final int port;
    private final FrameProvider provider;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    // track active clients so we can close them on shutdown
    private final List<Socket> activeClients = new CopyOnWriteArrayList<>();

    public interface FrameProvider { byte[] getFrame(); }

    public interface ClientListener {
        void onClientConnected();
        void onClientDisconnected();
    }

    private ClientListener clientListener;

    public void setClientListener(ClientListener l) { this.clientListener = l; }

    public MjpegHttpServer(int port, FrameProvider provider) {
        super("MjpegHttpServer");
        this.port = port;
        this.provider = provider;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            while (running) {
                try {
                    final Socket client = serverSocket.accept();
                    activeClients.add(client);
                    Thread t = new Thread(() -> handleClient(client), "mjpeg-client");
                    t.start();
                } catch (Exception acceptEx) {
                    // If we're shutting down the serverSocket.accept() will throw.
                    if (running) acceptEx.printStackTrace();
                    break;
                }
            }
        } catch (Exception e) {
            if (running) e.printStackTrace();
        } finally {
            // ensure any left-over clients are closed
            closeAllClients();
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleClient(Socket s) {
        if (clientListener != null) clientListener.onClientConnected();
        try (OutputStream out = s.getOutputStream();
             InputStream in = s.getInputStream()) {

            String header = "HTTP/1.0 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=ipcam\r\n\r\n";

            out.write(header.getBytes("UTF-8"));
            out.flush();

            // loop sending frames until client disconnects or server stops
            while (!s.isClosed() && running && !s.isOutputShutdown()) {
                byte[] jpeg = provider.getFrame();
                if (jpeg == null) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    continue;
                }

                String partHeader = "\r\n--ipcam\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpeg.length + "\r\n\r\n";

                out.write(partHeader.getBytes("UTF-8"));
                out.write(jpeg);
                out.flush();
            //    Log.d("MjpegHttpServer", "Sent frame len=" + jpeg.length + " to " + s.getRemoteSocketAddress());

                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            // client disconnected or I/O error - ignore silently
        } finally {
            // cleanup
            try { s.close(); } catch (Exception ignored) {}
            activeClients.remove(s);
            if (clientListener != null) clientListener.onClientDisconnected();
        }
    }

    /**
     * Shutdown the server: stop accepting new clients and close active clients.
     * After shutdown returns, the server thread will exit shortly.
     */
    public void shutdown() {
        running = false;
        // close server socket to break out of accept()
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        // close active client sockets so their threads exit quickly
        closeAllClients();
    }

    private void closeAllClients() {
        for (Socket c : activeClients) {
            try { c.close(); } catch (Exception ignored) {}
        }
        activeClients.clear();
    }
}
