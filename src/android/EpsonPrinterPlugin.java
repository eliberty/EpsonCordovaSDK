package com.eliberty.cordova.plugin.epsonusb;

import com.epson.epos2.printer.Printer;
import com.epson.epos2.Epos2Exception;
import com.epson.epos2.printer.ReceiveListener;
import com.epson.epos2.printer.PrinterStatusInfo;

import android.content.Context;
import android.util.Log;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EpsonPrinterPlugin extends CordovaPlugin {

    private static final int EPSON_VENDOR_ID = 0x04B8;
    private static final int EPSON_PRODUCT_ID = 0x0202;

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
            Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + e.getMessage());
        }
        return error;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d("EpsonPrinterPlugin", "execute called");
        Log.d("EpsonPrinterPlugin", "Args: " + args.toString());
        if (action.equals("printText")) {
            String toPrint = args.getJSONObject(0).getString("text");
            Log.d("EpsonPlugin", "Texte reçu pour impression : " + toPrint);
            this.printText(callbackContext, toPrint);
            return true;
        }
        if (action.equals("isPrinterAvailable")) {
            this.isPrinterAvailable(callbackContext);
            return true;
        }
        return false;
    }

    private void printText(CallbackContext callbackContext, String textToPrint) {
        Printer printer = null;
        
        // Diagnostic USB pour debug
        Context context = cordova.getActivity().getApplicationContext();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        boolean epsonFound = false;
        boolean hasPermission = false;
        String usbDiagnostic = "";
        
        if (usbManager != null && usbManager.getDeviceList() != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                usbDiagnostic += String.format("[VID:%04X PID:%04X] ", device.getVendorId(), device.getProductId());
                if (device.getVendorId() == EPSON_VENDOR_ID) {
                    epsonFound = true;
                    hasPermission = usbManager.hasPermission(device);
                }
            }
        }
        if (usbDiagnostic.isEmpty()) {
            usbDiagnostic = "Aucun périphérique USB détecté";
        }
        
        final String finalUsbDiagnostic = usbDiagnostic;
        final boolean finalEpsonFound = epsonFound;
        final boolean finalHasPermission = hasPermission;
        
        try {
            Log.d("EpsonPrinterPlugin", "Avant instanciation Printer");
            printer = new Printer(Printer.TM_T88, Printer.MODEL_ANK, context);
            Log.d("EpsonPrinterPlugin", "Printer instancié");

            printer.connect("USB:", Printer.PARAM_DEFAULT);
            printer.beginTransaction();
            printer.clearCommandBuffer();

            PrinterStatusInfo status = printer.getStatus();
            boolean isOnline = status != null && status.getConnection() == Printer.TRUE && status.getOnline() == Printer.TRUE;

            if (!isOnline) {
                JSONObject error = new JSONObject();
                try {
                    error.put("code", -1);
                    error.put("message", "Imprimante hors ligne : l'imprimante est connectée mais n'est pas prête à imprimer. Vérifiez le papier, le capot et l'état de l'imprimante");
                    error.put("context", "printText");
                    error.put("epsonDetected", finalEpsonFound);
                    error.put("usbPermission", finalHasPermission);
                    error.put("usbDevices", finalUsbDiagnostic);
                } catch (JSONException e) {
                    Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + e.getMessage());
                }
                callbackContext.error(error);
                cleanupPrinter(printer);
                return;
            }

            Pattern pattern = Pattern.compile("(<BOLD>.*?</BOLD>)|(<QRCODE>.*?</QRCODE>)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(textToPrint);

            int lastIndex = 0;
            while (matcher.find()) {
                // Texte avant la balise
                if (matcher.start() > lastIndex) {
                    String before = textToPrint.substring(lastIndex, matcher.start());
                    if (!before.isEmpty()) {
                        printer.addTextStyle(Printer.FALSE, Printer.FALSE, Printer.FALSE, Printer.COLOR_1);
                        printer.addTextAlign(Printer.ALIGN_CENTER);
                        printer.addText(before);
                    }
                }

                String match = matcher.group();
                if (match.startsWith("<BOLD>")) {
                    String boldText = match.substring(6, match.length() - 7);
                    printer.addTextStyle(Printer.FALSE, Printer.FALSE, Printer.TRUE, Printer.COLOR_1);
                    printer.addTextAlign(Printer.ALIGN_CENTER);
                    printer.addText(boldText);
                } else if (match.startsWith("<QRCODE>")) {
                    String qrContent = match.substring(8, match.length() - 9);
                    printer.addTextAlign(Printer.ALIGN_CENTER);
                    printer.addSymbol(qrContent, Printer.SYMBOL_QRCODE_MODEL_2,
                            Printer.LEVEL_L, 9, 1, 0);
                }

                lastIndex = matcher.end();
            }

            // Texte après la dernière balise
            if (lastIndex < textToPrint.length()) {
                String after = textToPrint.substring(lastIndex);
                if (!after.isEmpty()) {
                    printer.addTextStyle(Printer.FALSE, Printer.FALSE, Printer.FALSE, Printer.COLOR_1);
                    printer.addTextAlign(Printer.ALIGN_CENTER);
                    printer.addText(after);
                }
            }

            printer.addCut(Printer.CUT_FEED);
            printer.sendData(Printer.PARAM_DEFAULT);

            JSONObject success = new JSONObject();
            try {
                success.put("status", "sent");
                success.put("message", "Impression envoyée");
                success.put("epsonDetected", finalEpsonFound);
                success.put("usbPermission", finalHasPermission);
                success.put("usbDevices", finalUsbDiagnostic);
            } catch (JSONException e) {
                Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + e.getMessage());
            }
            callbackContext.success(success);

            printer.setReceiveEventListener(new ReceiveListener() {
                @Override
                public void onPtrReceive(Printer printer, int code, PrinterStatusInfo status, String printJobId) {
                    Log.d("EpsonPrinterPlugin", "Callback reçu, job terminé");
                    try {
                        printer.endTransaction();
                        printer.disconnect();
                    } catch (Epos2Exception e) {
                        int errorCode = e.getErrorStatus();
                        Log.e("EpsonPrinterPlugin", "Erreur callback impression - Code: " + errorCode + " - " + getEpsonErrorMessage(errorCode));
                        callbackContext.error(createErrorResponse(errorCode, "onPtrReceive"));
                    }
                }
            });

        } catch (Epos2Exception e) {
            int errorCode = e.getErrorStatus();
            Log.e("EpsonPrinterPlugin", "Erreur impression - Code: " + errorCode + " - " + getEpsonErrorMessage(errorCode));
            cleanupPrinter(printer);
            
            JSONObject error = new JSONObject();
            try {
                error.put("code", errorCode);
                error.put("message", getEpsonErrorMessage(errorCode));
                error.put("context", "printText");
                error.put("epsonDetected", finalEpsonFound);
                error.put("usbPermission", finalHasPermission);
                error.put("usbDevices", finalUsbDiagnostic);
            } catch (JSONException ex) {
                Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + ex.getMessage());
            }
            callbackContext.error(error);
        }
    }

    /**
     * Nettoie proprement les ressources de l'imprimante pour éviter les fuites
     */
    private void cleanupPrinter(Printer printer) {
        if (printer == null) return;
        try {
            printer.clearCommandBuffer();
        } catch (Exception ignored) {}
        try {
            printer.endTransaction();
        } catch (Exception ignored) {}
        try {
            printer.disconnect();
        } catch (Exception ignored) {}
    }

    private void isPrinterAvailable(CallbackContext callbackContext) {
        // Diagnostic USB avant connexion
        Context context = cordova.getActivity().getApplicationContext();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        boolean epsonFound = false;
        boolean hasPermission = false;
        String usbDiagnostic = "";
        
        if (usbManager != null && usbManager.getDeviceList() != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                usbDiagnostic += String.format("[VID:%04X PID:%04X] ", device.getVendorId(), device.getProductId());
                if (device.getVendorId() == EPSON_VENDOR_ID) {
                    epsonFound = true;
                    hasPermission = usbManager.hasPermission(device);
                    Log.d("EpsonPrinterPlugin", "Epson trouvé - VID: " + device.getVendorId() + " PID: " + device.getProductId());
                    Log.d("EpsonPrinterPlugin", "Permission accordée: " + hasPermission);
                }
            }
        }
        
        if (usbDiagnostic.isEmpty()) {
            usbDiagnostic = "Aucun périphérique USB détecté";
        }
        Log.d("EpsonPrinterPlugin", "Diagnostic USB: " + usbDiagnostic);
        
        final String finalUsbDiagnostic = usbDiagnostic;
        final boolean finalEpsonFound = epsonFound;
        final boolean finalHasPermission = hasPermission;

        Printer printer = null;
        try {
            printer = new Printer(Printer.TM_T88, Printer.MODEL_ANK, cordova.getActivity());
            printer.connect("USB:", Printer.PARAM_DEFAULT);
            printer.beginTransaction();
            printer.clearCommandBuffer();

            PrinterStatusInfo status = printer.getStatus();

            boolean isOnline = status != null && status.getConnection() == Printer.TRUE && status.getOnline() == Printer.TRUE;

            cleanupPrinter(printer);

            if (isOnline) {
                JSONObject success = new JSONObject();
                try {
                    success.put("status", "online");
                    success.put("message", "Imprimante disponible et prête");
                    success.put("epsonDetected", finalEpsonFound);
                    success.put("usbPermission", finalHasPermission);
                    success.put("usbDevices", finalUsbDiagnostic);
                } catch (JSONException e) {
                    Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + e.getMessage());
                }
                callbackContext.success(success);
            } else {
                JSONObject error = new JSONObject();
                try {
                    error.put("code", -1);
                    error.put("message", "Imprimante hors ligne : l'imprimante est connectée mais n'est pas prête. Vérifiez qu'elle n'est pas en erreur (papier, capot ouvert, etc.)");
                    error.put("context", "isPrinterAvailable");
                    error.put("usbDevices", finalUsbDiagnostic);
                } catch (JSONException e) {
                    Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + e.getMessage());
                }
                callbackContext.error(error);
            }

        } catch (Epos2Exception e) {
            int errorCode = e.getErrorStatus();
            Log.e("EpsonPrinterPlugin", "Erreur vérification disponibilité - Code: " + errorCode + " - " + getEpsonErrorMessage(errorCode));
            cleanupPrinter(printer);
            
            JSONObject error = new JSONObject();
            try {
                error.put("code", errorCode);
                error.put("message", getEpsonErrorMessage(errorCode));
                error.put("context", "isPrinterAvailable");
                error.put("epsonDetected", finalEpsonFound);
                error.put("usbPermission", finalHasPermission);
                error.put("usbDevices", finalUsbDiagnostic);
            } catch (JSONException ex) {
                Log.e("EpsonPrinterPlugin", "Erreur création JSON: " + ex.getMessage());
            }
            callbackContext.error(error);
        }
    }
}
