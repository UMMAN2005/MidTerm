package com.secure_web.servlets;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.UUID;

public class LoginServlet extends HttpServlet {
    private static final Logger logger = LogManager.getLogger(LoginServlet.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_TIME = 15 * 60 * 1000;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        String rememberMe = request.getParameter("rememberMe"); // Checkbox value

        try {
            Dotenv dotenv = Dotenv.load();
            String dbUrl = dotenv.get("DB_URL");
            String dbUsername = dotenv.get("DB_USERNAME");
            String dbPassword = dotenv.get("DB_PASSWORD");

            Class.forName("com.mysql.cj.jdbc.Driver");
            Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);

            if (isUserLockedOut(conn, username)) {
                logger.warn("User {} is locked out.", username);
                response.getWriter().println("Your account is locked. Please try again later.");
                return;
            }

            String sql = "SELECT username, role FROM Users WHERE username = ? AND password = SHA2(?, 256)";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, username);
            stmt.setString(2, password);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String role = rs.getString("role");
                resetFailedAttempts(conn, username);

                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                session = request.getSession(true);
                session.setAttribute("username", username);
                session.setAttribute("role", role);
                session.setMaxInactiveInterval(5 * 60);

                if ("on".equals(rememberMe)) {
                    String authToken = UUID.randomUUID().toString();
                    storeAuthToken(conn, username, authToken); // Save token to DB

                    Cookie userCookie = new Cookie("authToken", authToken);
                    userCookie.setMaxAge(7 * 24 * 60 * 60); // 7 days
                    userCookie.setHttpOnly(true);
                    response.addCookie(userCookie);
                }

                logger.info("User {} logged in successfully.", username);
                if ("admin".equals(role)) {
                    response.sendRedirect("adminWelcome");
                } else if ("moderator".equals(role)) {
                    response.sendRedirect("moderatorWelcome");
                } else {
                    response.sendRedirect("welcome");
                }
            } else {
                logger.warn("Failed login attempt for username: {}", username);
                incrementFailedAttempts(conn, username);
                response.getWriter().println("Invalid credentials.");
            }
        } catch (ClassNotFoundException | SQLException e) {
            logger.error("Error during login process.", e);
            response.getWriter().println("An error occurred.");
        }
    }

    private boolean isUserLockedOut(Connection conn, String username) throws SQLException {
        String sql = "SELECT failed_attempts, last_failed_attempt FROM LoginAttempts WHERE username = ?";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, username);
        ResultSet rs = stmt.executeQuery();

        if (rs.next()) {
            int failedAttempts = rs.getInt("failed_attempts");
            long lastFailedAttempt = rs.getLong("last_failed_attempt");
            long currentTime = System.currentTimeMillis();

            return failedAttempts >= MAX_FAILED_ATTEMPTS && (currentTime - lastFailedAttempt) < LOCKOUT_TIME;
        }
        return false;
    }

    private void incrementFailedAttempts(Connection conn, String username) throws SQLException {
        String sql = "INSERT INTO LoginAttempts (username, failed_attempts, last_failed_attempt) VALUES (?, 1, ?) " +
                "ON DUPLICATE KEY UPDATE failed_attempts = failed_attempts + 1, last_failed_attempt = ?";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, username);
        stmt.setLong(2, System.currentTimeMillis());
        stmt.setLong(3, System.currentTimeMillis());
        stmt.executeUpdate();
    }

    private void resetFailedAttempts(Connection conn, String username) throws SQLException {
        String sql = "UPDATE LoginAttempts SET failed_attempts = 0 WHERE username = ?";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, username);
        stmt.executeUpdate();
    }

    private void storeAuthToken(Connection conn, String username, String authToken) throws SQLException {
        String sql = "UPDATE Users SET auth_token = ? WHERE username = ?";
        PreparedStatement stmt = conn.prepareStatement(sql);
        stmt.setString(1, authToken);
        stmt.setString(2, username);
        stmt.executeUpdate();
    }
}
