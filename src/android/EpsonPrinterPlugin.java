package com.eliberty.cordova.plugin.epsonusb;

import com.epson.epos2.printer.Printer;
import com.epson.epos2.Epos2Exception;
import com.epson.epos2.printer.ReceiveListener;
import com.epson.epos2.printer.PrinterStatusInfo;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class EpsonPrinterPlugin extends CordovaPlugin implements ReceiveListener {

    private static final int EPSON_VENDOR_ID = 0x04B8;
    private static final int EPSON_PRODUCT_ID = 0x0202;
    
    // Product IDs connus pour les imprimantes TM-T88
    private static final int[] KNOWN_TM_T88_PIDS = {0x0202, 0x0e03, 0x0e15, 0x0e27, 0x0e28, 0x0e2a};

    // Instance unique de l'imprimante pour éviter les conflits
    private Printer mPrinter = null;
    // Sémaphore pour synchroniser l'accès à l'imprimante (1 seul permit = mutex)
    // Contrairement à ReentrantLock, un Semaphore peut être libéré par n'importe quel thread
    private final Semaphore printerSemaphore = new Semaphore(1, true);
    // Callback en attente pour la réponse asynchrone
    private volatile CallbackContext pendingCallbackContext = null;
    // Indicateur d'état de connexion
    private volatile boolean isConnected = false;
    // Indicateur de transaction en cours
    private volatile boolean isTransactionActive = false;
    // Indicateur que le sémaphore doit être libéré dans onPtrReceive
    private volatile boolean shouldReleaseSemaphoreInCallback = false;
    // Timeout pour acquérir le verrou (en secondes)
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    // Timeout de sécurité pour le callback (en secondes) - Recommandation Epson
    private static final int CALLBACK_TIMEOUT_SECONDS = 30;
    // Executor pour le timeout de sécurité
    private ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    // Future pour annuler le timeout si le callback arrive à temps
    private volatile ScheduledFuture<?> callbackTimeoutFuture = null;
    // Indicateur que le callback a été reçu (pour éviter double nettoyage)
    private final AtomicBoolean callbackReceived = new AtomicBoolean(false);
    // Timestamp du dernier sendData
    private volatile long lastSendDataTimestamp = 0;

    // Codes d'erreur Epson ePOS2 SDK
    private static final int ERR_SUCCESS = 0;
    private static final int ERR_PARAM = 1;
    private static final int ERR_CONNECT = 2;
    private static final int ERR_TIMEOUT = 3;
    private static final int ERR_MEMORY = 4;
    private static final int ERR_ILLEGAL = 5;
    private static final int ERR_PROCESSING = 6;
    private static final int ERR_NOT_FOUND = 7;
    private static final int ERR_IN_USE = 8;
    private static final int ERR_TYPE_INVALID = 9;
    private static final int ERR_DISCONNECT = 10;
    private static final int ERR_ALREADY_OPENED = 11;
    private static final int ERR_ALREADY_USED = 12;
    private static final int ERR_BOX_COUNT_OVER = 13;
    private static final int ERR_BOX_CLIENT_OVER = 14;
    private static final int ERR_UNSUPPORTED = 15;
    private static final int ERR_FAILURE = 16;

    /**
     * Convertit un code d'erreur Epson en message compréhensible
     */
    private String getEpsonErrorMessage(int errorCode) {
        switch (errorCode) {
            case ERR_SUCCESS:
                return "Opération réussie";
            case ERR_PARAM:
                return "Paramètre invalide : un des paramètres passés à la fonction est incorrect";
            case ERR_CONNECT:
                return "Erreur de connexion : impossible de se connecter à l'imprimante. Vérifiez que l'imprimante est allumée, connectée via USB et que les permissions USB sont accordées";
            case ERR_TIMEOUT:
                return "Délai d'attente dépassé : l'imprimante n'a pas répondu dans le temps imparti. Vérifiez la connexion et réessayez";
            case ERR_MEMORY:
                return "Mémoire insuffisante : l'appareil n'a pas assez de mémoire disponible pour effectuer l'opération";
            case ERR_ILLEGAL:
                return "État illégal : une opération a été appelée dans un ordre incorrect (ex: impression sans connexion préalable)";
            case ERR_PROCESSING:
                return "Traitement en cours : une autre opération est déjà en cours d'exécution. Attendez la fin de l'opération précédente";
            case ERR_NOT_FOUND:
                return "Imprimante non trouvée : aucune imprimante Epson compatible n'a été détectée sur le port USB";
            case ERR_IN_USE:
                return "Imprimante occupée : l'imprimante est déjà utilisée par une autre application ou un autre processus";
            case ERR_TYPE_INVALID:
                return "Type d'imprimante invalide : le modèle d'imprimante spécifié n'est pas compatible";
            case ERR_DISCONNECT:
                return "Erreur de déconnexion : impossible de fermer proprement la connexion avec l'imprimante";
            case ERR_ALREADY_OPENED:
                return "Déjà connecté : une connexion avec l'imprimante est déjà établie";
            case ERR_ALREADY_USED:
                return "Déjà utilisé : la ressource demandée est déjà en cours d'utilisation";
            case ERR_BOX_COUNT_OVER:
                return "Limite de boîtes dépassée : le nombre maximum de boîtes de connexion est atteint";
            case ERR_BOX_CLIENT_OVER:
                return "Limite de clients dépassée : le nombre maximum de clients connectés est atteint";
            case ERR_UNSUPPORTED:
                return "Fonctionnalité non supportée : cette fonction n'est pas disponible sur ce modèle d'imprimante";
            case ERR_FAILURE:
                return "Échec général : une erreur interne s'est produite. Redémarrez l'imprimante et l'application";
            default:
                return "Erreur inconnue (code: " + errorCode + ")";
        }
    }

    /**
     * Crée un objet JSON d'erreur détaillé
     */
    private JSONObject createErrorResponse(int errorCode, String context) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", errorCode);
            error.put("message", getEpsonErrorMessage(errorCode));
            error.put("context", context);
        } catch (JSONException e) {
            // Ignore JSON error
        }
        return error;
    }

    /**
     * Callback de réception de l'imprimante (ReceiveListener)
     * Appelé après que sendData ait terminé l'impression
     * ATTENTION: Ce callback est appelé sur un thread DIFFÉRENT du thread appelant
     * 
     * IMPORTANT (recommandation support Epson): Le nettoyage doit TOUJOURS être effectué
     * dans cet ordre, même en cas d'erreur :
     * 1. endTransaction()
     * 2. disconnect()
     * 3. clearCommandBuffer()
     */
    @Override
    public void onPtrReceive(Printer printer, int code, PrinterStatusInfo status, String printJobId) {
        // Marquer que le callback a été reçu (pour éviter double nettoyage par le timeout)
        if (!callbackReceived.compareAndSet(false, true)) {
            // Le callback a déjà été traité (probablement par le timeout)
            return;
        }
        
        // Annuler le timeout de sécurité car le callback est arrivé
        if (callbackTimeoutFuture != null) {
            callbackTimeoutFuture.cancel(false);
            callbackTimeoutFuture = null;
        }
        
        // Sauvegarder les références localement car elles peuvent être modifiées par un autre thread
        CallbackContext callback = pendingCallbackContext;
        pendingCallbackContext = null;
        boolean shouldRelease = shouldReleaseSemaphoreInCallback;
        shouldReleaseSemaphoreInCallback = false;
        
        // ============================================================
        // NETTOYAGE OBLIGATOIRE (même en cas d'erreur) - Ordre Epson SDK
        // ============================================================
        
        // 1. Fin de la transaction - DOIT être fait EN PREMIER
        try {
            if (isTransactionActive && printer != null) {
                printer.endTransaction();
            }
        } catch (Epos2Exception e) {
            // Continue cleanup
        } finally {
            isTransactionActive = false;
        }
        
        // 2. Déconnexion
        try {
            if (printer != null && isConnected) {
                printer.disconnect();
            }
        } catch (Epos2Exception e) {
            // Continue cleanup
        } finally {
            isConnected = false;
        }
        
        // 3. Vider le buffer de commandes - EN DERNIER (nettoie l'état interne du SDK)
        try {
            if (printer != null) {
                printer.clearCommandBuffer();
            }
        } catch (Exception e) {
            // Continue cleanup
        }
        
        // ============================================================
        // FIN DU NETTOYAGE
        // ============================================================
        
        // Libérer le sémaphore si nécessaire
        // Le Semaphore peut être libéré par n'importe quel thread (contrairement à ReentrantLock)
        if (shouldRelease) {
            printerSemaphore.release();
        }
        
        // Notifier le résultat au JavaScript
        if (callback != null) {
            if (code == 0) {
                JSONObject success = new JSONObject();
                try {
                    success.put("status", "printed");
                    success.put("message", "Impression réussie");
                    success.put("printJobId", printJobId);
                } catch (JSONException e) {
                    // Ignore JSON error
                }
                callback.success(success);
            } else {
                JSONObject error = new JSONObject();
                try {
                    error.put("code", code);
                    error.put("context", "onPtrReceive");
                } catch (JSONException e) {
                    // Ignore JSON error
                }
                callback.error(error);
            }
        }
    }


    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("printText")) {
            String toPrint = args.getJSONObject(0).getString("text");
            this.printText(callbackContext, toPrint);
            return true;
        }
        if (action.equals("isPrinterAvailable")) {
            this.isPrinterAvailable(callbackContext);
            return true;
        }
        return false;
    }

    /**
     * Initialise l'imprimante si nécessaire
     */
    private boolean initializePrinter(Context context) {
        if (mPrinter != null) {
            return true;
        }
        
        try {
            mPrinter = new Printer(Printer.TM_T88, Printer.MODEL_ANK, context);
            // Enregistrer le listener AVANT toute opération
            mPrinter.setReceiveEventListener(this);
            return true;
        } catch (Epos2Exception e) {
            mPrinter = null;
            return false;
        }
    }
    
    /**
     * Connecte l'imprimante
     */
    private boolean connectPrinter() {
        if (mPrinter == null) {
            return false;
        }
        
        if (isConnected) {
            // Vérifier que la connexion est toujours valide
            try {
                PrinterStatusInfo status = mPrinter.getStatus();
                if (status != null && status.getConnection() == Printer.TRUE) {
                    return true;
                }
                // La connexion semble perdue, réinitialiser le flag
                isConnected = false;
            } catch (Exception e) {
                isConnected = false;
            }
        }
        
        // Tentative de connexion avec retry
        int maxRetries = 2;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Timeout de connexion augmenté pour plus de stabilité
                mPrinter.connect("USB:", 15000);
                isConnected = true;
                return true;
            } catch (Epos2Exception e) {
                int errorCode = e.getErrorStatus();
                
                // Si déjà connecté, considérer comme OK
                if (errorCode == ERR_ALREADY_OPENED) {
                    isConnected = true;
                    return true;
                }
                
                // Si c'est une erreur récupérable et qu'on a des retries restants
                if (attempt < maxRetries && (errorCode == ERR_CONNECT || errorCode == ERR_TIMEOUT)) {
                    try {
                        // Petit délai avant de réessayer
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Déconnecte l'imprimante proprement
     */
    private void disconnectPrinter() {
        if (mPrinter == null || !isConnected) {
            return;
        }
        
        try {
            mPrinter.disconnect();
            isConnected = false;
        } catch (Exception e) {
            isConnected = false;
        }
    }
    
    /**
     * Libère complètement les ressources de l'imprimante
     */
    private void releasePrinter() {
        if (mPrinter == null) {
            return;
        }
        
        try {
            mPrinter.clearCommandBuffer();
        } catch (Exception e) {
            // Ignore error
        }
        
        if (isTransactionActive) {
            try {
                mPrinter.endTransaction();
                isTransactionActive = false;
            } catch (Exception e) {
                // Ignore error
            }
        }
        
        disconnectPrinter();
        
        try {
            mPrinter.setReceiveEventListener(null);
        } catch (Exception e) {
            // Ignore error
        }
        
        mPrinter = null;
    }
    
    /**
     * Nettoyage forcé après timeout du callback (recommandation support Epson)
     * Appelé quand onPtrReceive n'est pas reçu dans le délai imparti
     * DOIT effectuer le même nettoyage que onPtrReceive pour éviter ERR_CONNECT
     */
    private void forceCleanupAfterTimeout() {
        Printer printer = mPrinter;
        
        // 1. Fin de la transaction
        try {
            if (isTransactionActive && printer != null) {
                printer.endTransaction();
            }
        } catch (Exception e) {
            // Continue cleanup
        } finally {
            isTransactionActive = false;
        }
        
        // 2. Déconnexion
        try {
            if (printer != null && isConnected) {
                printer.disconnect();
            }
        } catch (Exception e) {
            // Continue cleanup
        } finally {
            isConnected = false;
        }
        
        // 3. Vider le buffer de commandes (nettoie l'état interne du SDK)
        try {
            if (printer != null) {
                printer.clearCommandBuffer();
            }
        } catch (Exception e) {
            // Continue cleanup
        }
        
        // 4. forceRecover() - UNIQUEMENT en cas de timeout (recommandation Support Epson)
        // forceRecover() est réservé aux situations de récupération après erreur persistante
        // Le timeout du callback est exactement ce type de situation
        try {
            if (printer != null) {
                printer.forceRecover(3000);
            }
        } catch (Exception e) {
            // Continue cleanup
        }
        
        // Libérer le sémaphore si nécessaire
        if (shouldReleaseSemaphoreInCallback) {
            shouldReleaseSemaphoreInCallback = false;
            printerSemaphore.release();
        }
        
        // Nettoyer les références
        pendingCallbackContext = null;
    }
    
    /**
     * Collecte les informations de diagnostic USB
     */
    private JSONObject getUsbDiagnostics(Context context) {
        JSONObject diag = new JSONObject();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        
        boolean epsonFound = false;
        boolean hasPermission = false;
        String usbDiagnostic = "";
        int usbDeviceCount = 0;
        int epsonProductId = 0;
        String epsonDeviceName = "";
        boolean isKnownTmT88 = false;
        
        if (usbManager == null) {
            usbDiagnostic = "UsbManager non disponible";
        } else if (usbManager.getDeviceList() == null) {
            usbDiagnostic = "Liste USB null";
        } else {
            usbDeviceCount = usbManager.getDeviceList().size();
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                usbDiagnostic += String.format("[VID:%04X PID:%04X] ", device.getVendorId(), device.getProductId());
                if (device.getVendorId() == EPSON_VENDOR_ID) {
                    epsonFound = true;
                    epsonProductId = device.getProductId();
                    epsonDeviceName = device.getDeviceName();
                    hasPermission = usbManager.hasPermission(device);
                    for (int knownPid : KNOWN_TM_T88_PIDS) {
                        if (epsonProductId == knownPid) {
                            isKnownTmT88 = true;
                            break;
                        }
                    }
                }
            }
        }
        
        if (usbDiagnostic.isEmpty()) {
            usbDiagnostic = "Aucun périphérique USB détecté";
        }
        
        try {
            diag.put("epsonDetected", epsonFound);
            diag.put("usbPermission", hasPermission);
            diag.put("usbDevices", usbDiagnostic);
            diag.put("usbDeviceCount", usbDeviceCount);
            diag.put("epsonProductId", String.format("0x%04X", epsonProductId));
            diag.put("epsonDeviceName", epsonDeviceName);
            diag.put("isKnownTmT88", isKnownTmT88);
        } catch (JSONException e) {
            // Ignore JSON error
        }
        
        return diag;
    }

    private void printText(final CallbackContext callbackContext, final String textToPrint) {
        // Exécuter sur un thread séparé car connect/disconnect ne doivent pas être sur le main thread
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                printTextInternal(callbackContext, textToPrint);
            }
        });
    }
    
    private void printTextInternal(CallbackContext callbackContext, String textToPrint) {
        Context context = cordova.getActivity().getApplicationContext();
        JSONObject diagnostics = getUsbDiagnostics(context);
        
        // Essayer d'acquérir le sémaphore avec timeout
        boolean semaphoreAcquired = false;
        try {
            semaphoreAcquired = printerSemaphore.tryAcquire(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (!semaphoreAcquired) {
            JSONObject error = new JSONObject();
            try {
                error.put("code", ERR_PROCESSING);
                error.put("message", "Une impression est déjà en cours. Veuillez patienter.");
                error.put("context", "printText");
                mergeJson(error, diagnostics);
            } catch (JSONException e) {
                // Ignore JSON error
            }
            callbackContext.error(error);
            return;
        }
        
        try {
            // Réinitialiser l'état si l'imprimante est dans un état incohérent
            // (connexion perdue mais objet non nettoyé, ou transaction précédente non terminée)
            if (mPrinter != null) {
                if (isTransactionActive || !isConnected) {
                    releasePrinter();
                }
            }
            
            // Initialiser l'imprimante si nécessaire
            if (!initializePrinter(context)) {
                JSONObject error = new JSONObject();
                try {
                    error.put("code", ERR_FAILURE);
                    error.put("message", "Impossible d'initialiser l'imprimante");
                    error.put("context", "printText");
                    mergeJson(error, diagnostics);
                } catch (JSONException e) {
                    // Ignore JSON error
                }
                callbackContext.error(error);
                printerSemaphore.release();
                return;
            }
            
            // Connecter l'imprimante
            if (!connectPrinter()) {
                JSONObject error = new JSONObject();
                try {
                    error.put("code", ERR_CONNECT);
                    error.put("message", getEpsonErrorMessage(ERR_CONNECT));
                    error.put("context", "printText");
                    mergeJson(error, diagnostics);
                } catch (JSONException e) {
                    // Ignore JSON error
                }
                releasePrinter();
                callbackContext.error(error);
                printerSemaphore.release();
                return;
            }
            
            // Vider le buffer AVANT de démarrer la transaction (selon Epson SDK)
            try {
                mPrinter.clearCommandBuffer();
            } catch (Exception e) {
                // Continue anyway
            }
            
            // Vérifier le statut de l'imprimante AVANT la transaction
            try {
                PrinterStatusInfo status = mPrinter.getStatus();
                boolean isOnline = status != null && status.getConnection() == Printer.TRUE && status.getOnline() == Printer.TRUE;
                
                if (!isOnline) {
                    // NOTE (Support Epson): Ne PAS utiliser forceRecover() dans le flux normal.
                    // forceRecover() est réservé aux situations de récupération après erreur persistante.
                    // En cas d'imprimante hors ligne, retourner une erreur claire à l'utilisateur.
                    JSONObject error = new JSONObject();
                    try {
                        error.put("code", -1);
                        error.put("message", "Imprimante hors ligne : vérifiez le papier, le capot et l'état de l'imprimante");
                        error.put("context", "printText");
                        error.put("printerStatus", status != null ? "connection=" + status.getConnection() + ", online=" + status.getOnline() : "null");
                        mergeJson(error, diagnostics);
                    } catch (JSONException ex) {
                        // Ignore JSON error
                    }
                    releasePrinter();
                    callbackContext.error(error);
                    printerSemaphore.release();
                    return;
                }
            } catch (Exception e) {
                // Continuer malgré l'erreur - on essaiera d'imprimer quand même
            }
            
            // Démarrer la transaction APRÈS clearCommandBuffer et vérification statut
            try {
                mPrinter.beginTransaction();
                isTransactionActive = true;
            } catch (Epos2Exception e) {
                int errorCode = e.getErrorStatus();
                JSONObject error = new JSONObject();
                try {
                    error.put("code", errorCode);
                    error.put("message", getEpsonErrorMessage(errorCode));
                    error.put("context", "beginTransaction");
                    mergeJson(error, diagnostics);
                } catch (JSONException ex) {
                    // Ignore JSON error
                }
                releasePrinter();
                callbackContext.error(error);
                printerSemaphore.release();
                return;
            }
            
            // Préparer les commandes d'impression
            try {
                Pattern pattern = Pattern.compile("(<BOLD>.*?</BOLD>)|(<QRCODE>.*?</QRCODE>)", Pattern.DOTALL);
                Matcher matcher = pattern.matcher(textToPrint);

                int lastIndex = 0;
                while (matcher.find()) {
                    if (matcher.start() > lastIndex) {
                        String before = textToPrint.substring(lastIndex, matcher.start());
                        if (!before.isEmpty()) {
                            mPrinter.addTextStyle(Printer.FALSE, Printer.FALSE, Printer.FALSE, Printer.COLOR_1);
                            mPrinter.addTextAlign(Printer.ALIGN_CENTER);
                            mPrinter.addText(before);
                        }
                    }

                    String match = matcher.group();
                    if (match.startsWith("<BOLD>")) {
                        String boldText = match.substring(6, match.length() - 7);
                        mPrinter.addTextStyle(Printer.FALSE, Printer.FALSE, Printer.TRUE, Printer.COLOR_1);
                        mPrinter.addTextAlign(Printer.ALIGN_CENTER);
                        mPrinter.addText(boldText);
                    } else if (match.startsWith("<QRCODE>")) {
                        String qrContent = match.substring(8, match.length() - 9);
                        mPrinter.addTextAlign(Printer.ALIGN_CENTER);
                        mPrinter.addSymbol(qrContent, Printer.SYMBOL_QRCODE_MODEL_2,
                                Printer.LEVEL_L, 9, 1, 0);
                    }

                    lastIndex = matcher.end();
                }

                if (lastIndex < textToPrint.length()) {
                    String after = textToPrint.substring(lastIndex);
                    if (!after.isEmpty()) {
                        mPrinter.addTextStyle(Printer.FALSE, Printer.FALSE, Printer.FALSE, Printer.COLOR_1);
                        mPrinter.addTextAlign(Printer.ALIGN_CENTER);
                        mPrinter.addText(after);
                    }
                }

                mPrinter.addCut(Printer.CUT_FEED);
                
            } catch (Epos2Exception e) {
                int errorCode = e.getErrorStatus();
                JSONObject error = new JSONObject();
                try {
                    error.put("code", errorCode);
                    error.put("message", getEpsonErrorMessage(errorCode));
                    error.put("context", "addPrintCommands");
                    mergeJson(error, diagnostics);
                } catch (JSONException ex) {
                    // Ignore JSON error
                }
                releasePrinter();
                callbackContext.error(error);
                printerSemaphore.release();
                return;
            }
            
            // Envoyer les données - le callback sera géré dans onPtrReceive
            // IMPORTANT: stocker le callback AVANT sendData
            pendingCallbackContext = callbackContext;
            
            // Réinitialiser l'indicateur de callback reçu
            callbackReceived.set(false);
            
            try {
                // Marquer que le sémaphore doit être libéré dans le callback
                shouldReleaseSemaphoreInCallback = true;
                
                // Démarrer le timeout de sécurité AVANT sendData
                // Si le callback n'est pas reçu dans CALLBACK_TIMEOUT_SECONDS, forcer le nettoyage
                final CallbackContext timeoutCallback = callbackContext;
                final JSONObject timeoutDiagnostics = diagnostics;
                lastSendDataTimestamp = System.currentTimeMillis();
                
                callbackTimeoutFuture = timeoutExecutor.schedule(new Runnable() {
                    @Override
                    public void run() {
                        // Vérifier si le callback n'a pas été reçu
                        if (callbackReceived.compareAndSet(false, true)) {
                            long elapsed = System.currentTimeMillis() - lastSendDataTimestamp;
                            
                            // Forcer le nettoyage même sans callback
                            forceCleanupAfterTimeout();
                            
                            // Notifier l'erreur au JavaScript
                            if (timeoutCallback != null) {
                                JSONObject error = new JSONObject();
                                try {
                                    error.put("code", ERR_TIMEOUT);
                                    error.put("message", "Timeout: le callback d'impression n'a pas été reçu après " + CALLBACK_TIMEOUT_SECONDS + " secondes. Nettoyage forcé effectué.");
                                    error.put("context", "callbackTimeout");
                                    error.put("elapsedMs", elapsed);
                                    mergeJson(error, timeoutDiagnostics);
                                } catch (JSONException ex) {
                                    // Ignore JSON error
                                }
                                timeoutCallback.error(error);
                            }
                        }
                    }
                }, CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                
                // Timeout augmenté pour l'impression
                mPrinter.sendData(Printer.PARAM_DEFAULT);
                // NE PAS appeler callbackContext.success() ici !
                // Le résultat sera notifié dans onPtrReceive
                // NE PAS libérer le sémaphore ici, il sera libéré dans onPtrReceive
                
            } catch (Epos2Exception e) {
                int errorCode = e.getErrorStatus();
                
                // Annuler le timeout car sendData a échoué immédiatement
                if (callbackTimeoutFuture != null) {
                    callbackTimeoutFuture.cancel(false);
                    callbackTimeoutFuture = null;
                }
                callbackReceived.set(true); // Marquer comme traité
                
                shouldReleaseSemaphoreInCallback = false;
                pendingCallbackContext = null;
                
                JSONObject error = new JSONObject();
                try {
                    error.put("code", errorCode);
                    error.put("message", getEpsonErrorMessage(errorCode));
                    error.put("context", "sendData");
                    mergeJson(error, diagnostics);
                } catch (JSONException ex) {
                    // Ignore JSON error
                }
                releasePrinter();
                callbackContext.error(error);
                printerSemaphore.release();
            }
            
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            try {
                error.put("code", ERR_FAILURE);
                error.put("message", "Erreur inattendue: " + e.getMessage());
                error.put("context", "printText");
                mergeJson(error, diagnostics);
            } catch (JSONException ex) {
                // Ignore JSON error
            }
            
            shouldReleaseSemaphoreInCallback = false;
            pendingCallbackContext = null;
            releasePrinter();
            callbackContext.error(error);
            printerSemaphore.release();
        }
    }
    
    /**
     * Fusionne deux objets JSON
     */
    private void mergeJson(JSONObject target, JSONObject source) {
        try {
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                target.put(key, source.get(key));
            }
        } catch (JSONException e) {
            // Ignore merge error
        }
    }

    private void isPrinterAvailable(final CallbackContext callbackContext) {
        // Exécuter sur un thread séparé car connect/disconnect ne doivent pas être sur le main thread
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                isPrinterAvailableInternal(callbackContext);
            }
        });
    }
    
    private void isPrinterAvailableInternal(CallbackContext callbackContext) {
        Context context = cordova.getActivity().getApplicationContext();
        JSONObject diagnostics = getUsbDiagnostics(context);
        
        // Essayer d'acquérir le sémaphore avec timeout court pour la vérification
        boolean lockAcquired = false;
        try {
            lockAcquired = printerSemaphore.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (!lockAcquired) {
            // Si on ne peut pas acquérir le verrou, une impression est en cours
            // L'imprimante est donc "disponible" mais occupée
            JSONObject response = new JSONObject();
            try {
                response.put("status", "busy");
                response.put("message", "Imprimante occupée - une impression est en cours");
                mergeJson(response, diagnostics);
            } catch (JSONException e) {
                // Ignore JSON error
            }
            callbackContext.success(response);
            return;
        }
        
        Printer testPrinter = null;
        try {
            testPrinter = new Printer(Printer.TM_T88, Printer.MODEL_ANK, context);
            testPrinter.connect("USB:", 10000);
            
            PrinterStatusInfo status = testPrinter.getStatus();
            boolean isOnline = status != null && status.getConnection() == Printer.TRUE && status.getOnline() == Printer.TRUE;
            
            // Nettoyage immédiat
            try {
                testPrinter.disconnect();
            } catch (Exception e) {
                // Ignore disconnect error
            }
            testPrinter = null;
            
            if (isOnline) {
                JSONObject success = new JSONObject();
                try {
                    success.put("status", "online");
                    success.put("message", "Imprimante disponible et prête");
                    mergeJson(success, diagnostics);
                } catch (JSONException e) {
                    // Ignore JSON error
                }
                callbackContext.success(success);
            } else {
                JSONObject error = new JSONObject();
                try {
                    error.put("code", -1);
                    error.put("message", "Imprimante hors ligne : vérifiez qu'elle n'est pas en erreur (papier, capot ouvert, etc.)");
                    error.put("context", "isPrinterAvailable");
                    mergeJson(error, diagnostics);
                } catch (JSONException e) {
                    // Ignore JSON error
                }
                callbackContext.error(error);
            }

        } catch (Exception e) {
            int errorCode = -1;
            if (e instanceof Epos2Exception) {
                errorCode = ((Epos2Exception) e).getErrorStatus();
            }
            
            // Nettoyage en cas d'erreur
            if (testPrinter != null) {
                try {
                    testPrinter.disconnect();
                } catch (Exception ex) {
                    // Ignore disconnect error
                }
            }
            
            JSONObject error = new JSONObject();
            try {
                error.put("code", errorCode);
                error.put("message", getEpsonErrorMessage(errorCode));
                error.put("context", "isPrinterAvailable");
                mergeJson(error, diagnostics);
            } catch (JSONException ex) {
                // Ignore JSON error
            }
            callbackContext.error(error);
        } finally {
            printerSemaphore.release();
        }
    }
    
    /**
     * Appelé lors de la destruction du plugin pour libérer les ressources
     */
    @Override
    public void onDestroy() {
        // Annuler tout timeout en attente
        if (callbackTimeoutFuture != null) {
            callbackTimeoutFuture.cancel(false);
            callbackTimeoutFuture = null;
        }
        
        // Arrêter l'executor de timeout
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
        }
        
        releasePrinter();
        super.onDestroy();
    }
    
    /**
     * Appelé lors de la mise en pause de l'application
     */
    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
    }
    
    /**
     * Appelé lors de la reprise de l'application
     */
    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
    }
}
