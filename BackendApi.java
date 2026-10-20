package com.temple.donation.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared HTTP client for Temple Donation Manager.
 *
 * Emulator base URL: http://10.0.2.2:8086
 *
 * TESTING ONLY:
 * This version automatically logs in with the test admin credentials,
 * saves the returned JWT, and attaches it to API requests.
 * Do not ship embedded admin credentials in a production APK.
 */
public final class BackendApi {

    private static final String BASE_URL = "http://10.0.2.2:8086";
    private static final String LOGIN_PATH = "/api/auth/login";

    // TESTING ONLY. Remove these credentials before production release.
    private static final String ADMIN_USERNAME = "admin@temple.local";
    private static final String ADMIN_PASSWORD = "Admin@12345";

    private static final String PREF_NAME = "auth";
    private static final String TOKEN_KEY = "token";

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(3);
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private BackendApi() { }

    public interface Callback {
        void onSuccess(String response);
        void onError(String message);
    }

    public static void get(Context context, String path, Callback callback) {
        request(context, "GET", path, null, callback);
    }

    public static void post(Context context, String path,
                            JSONObject body, Callback callback) {
        request(context, "POST", path, body, callback);
    }

    public static void put(Context context, String path,
                           JSONObject body, Callback callback) {
        request(context, "PUT", path, body, callback);
    }

    public static void patch(Context context, String path,
                             JSONObject body, Callback callback) {
        request(context, "PATCH", path, body, callback);
    }

    public static void delete(Context context, String path,
                              Callback callback) {
        request(context, "DELETE", path, null, callback);
    }

    /** Save a raw JWT or a value prefixed with "Bearer ". */
    public static void saveToken(Context context, String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT token is empty");
        }

        String cleanToken = token.trim();
        if (cleanToken.regionMatches(true, 0, "Bearer ", 0, 7)) {
            cleanToken = cleanToken.substring(7).trim();
        }

        context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(TOKEN_KEY, cleanToken)
                .apply();
    }

    public static String getToken(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(TOKEN_KEY, null);
    }

    public static void clearToken(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(TOKEN_KEY)
                .apply();
    }

    /**
     * Authenticate using the test admin credentials, save the JWT, and invoke
     * callback. Assumes login JSON is {"username":"...", "password":"..."}
     * and response JSON contains {"token":"..."}.
     * Adjust these field names if your backend DTO uses different names.
     */
    public static void loginAndSaveToken(Context context, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", ADMIN_USERNAME);
            body.put("password", ADMIN_PASSWORD);
        } catch (Exception e) {
            postError(callback, "Could not prepare login: " + e.getMessage());
            return;
        }

        sendRequest(context, "POST", LOGIN_PATH, body, false, new Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject json = new JSONObject(response);
                    String token = json.optString("token", "");

                    // Support common alternate response property names.
                    if (token.isEmpty()) token = json.optString("accessToken", "");
                    if (token.isEmpty()) token = json.optString("jwt", "");

                    if (token.isEmpty()) {
                        postError(callback,
                                "Login response did not contain token/accessToken/jwt. Response: "
                                        + response);
                        return;
                    }

                    saveToken(context, token);
                    postSuccess(callback, "Admin login successful; JWT saved.");
                } catch (Exception e) {
                    postError(callback,
                            "Could not parse login response: " + e.getMessage());
                }
            }

            @Override
            public void onError(String message) {
                postError(callback, "Admin login failed: " + message);
            }
        });
    }

    /**
     * Make an authenticated request. If no token is saved, automatically
     * log in first and then retry the requested API call once.
     */
    public static void request(Context context, String method, String path,
                               JSONObject body, Callback callback) {
        String token = getToken(context);
        if (token == null || token.trim().isEmpty()) {
            loginAndSaveToken(context, new Callback() {
                @Override
                public void onSuccess(String response) {
                    sendRequest(context, method, path, body, true, callback);
                }

                @Override
                public void onError(String message) {
                    postError(callback, message);
                }
            });
        } else {
            sendRequest(context, method, path, body, true, callback);
        }
    }

    private static void sendRequest(Context context, String method, String path,
                                    JSONObject body, boolean attachToken,
                                    Callback callback) {
        Context appContext = context.getApplicationContext();

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String normalizedPath = path.startsWith("/") ? path : "/" + path;
                URL url = new URL(BASE_URL + normalizedPath);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");

                if (attachToken) {
                    String token = getToken(appContext);
                    if (token != null && !token.trim().isEmpty()) {
                        String cleanToken = token.trim();
                        if (cleanToken.regionMatches(true, 0, "Bearer ", 0, 7)) {
                            cleanToken = cleanToken.substring(7).trim();
                        }
                        connection.setRequestProperty(
                                "Authorization", "Bearer " + cleanToken);
                    }
                }

                if (body != null) {
                    connection.setRequestProperty(
                            "Content-Type", "application/json; charset=UTF-8");
                    connection.setDoOutput(true);
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(bytes);
                    }
                }

                int status = connection.getResponseCode();
                InputStream stream = (status >= 200 && status < 300)
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String response = readStream(stream);

                final int responseCode = status;
                final String responseBody = response;

                // If the token expired, re-login once and retry this request.
                if (attachToken && responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearToken(appContext);
                    loginAndSaveToken(appContext, new Callback() {
                        @Override
                        public void onSuccess(String ignored) {
                            sendRequest(appContext, method, path, body, true, callback);
                        }

                        @Override
                        public void onError(String message) {
                            postError(callback, "Session refresh failed: " + message);
                        }
                    });
                    return;
                }

                MAIN_HANDLER.post(() -> {
                    if (responseCode >= 200 && responseCode < 300) {
                        callback.onSuccess(responseBody);
                    } else {
                        callback.onError("HTTP " + responseCode
                                + (responseBody.isEmpty() ? "" : ": " + responseBody));
                    }
                });

            } catch (Exception e) {
                String message = e.getMessage() == null
                        ? "Unable to connect to backend"
                        : e.getMessage();
                postError(callback, message);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static void postSuccess(Callback callback, String message) {
        MAIN_HANDLER.post(() -> callback.onSuccess(message));
    }

    private static void postError(Callback callback, String message) {
        MAIN_HANDLER.post(() -> callback.onError(message));
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }
}
