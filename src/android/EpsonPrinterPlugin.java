package com.eliberty.cordova.plugin.epsonusb;

import com.epson.epos2.printer.Printer;
import com.epson.epos2.Epos2Exception;
import com.epson.epos2.printer.ReceiveListener;
import com.epson.epos2.printer.PrinterStatusInfo;

import android.content.Context;
import android.util.Log;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EpsonPrinterPlugin extends CordovaPlugin {

    private static final int EPSON_VENDOR_ID = 0x04B8;
    private static final int EPSON_PRODUCT_ID = 0x0202;

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
        try {
            Log.d("EpsonPrinterPlugin", "Avant instanciation Printer");
            Context context = cordova.getActivity().getApplicationContext();
            Printer printer = new Printer(Printer.TM_T88, Printer.MODEL_ANK, context);
            Log.d("EpsonPrinterPlugin", "Printer instancié");

            printer.connect("USB:", Printer.PARAM_DEFAULT);
            printer.beginTransaction();

            PrinterStatusInfo status = printer.getStatus();

            boolean isOnline = status != null && status.getConnection() == Printer.TRUE && status.getOnline() == Printer.TRUE;

            if (!isOnline) {
                callbackContext.error("Imprimante hors ligne");
                printer.endTransaction();
                printer.disconnect();
                return;
            }

            Pattern qrPattern = Pattern.compile("<QRCODE>(.*?)</QRCODE>", Pattern.DOTALL);
            Matcher matcher = qrPattern.matcher(textToPrint);

            int currentIndex = 0;
            while (matcher.find()) {
                int qrStart = matcher.start();
                int qrEnd = matcher.end();
                String textBefore = textToPrint.substring(currentIndex, qrStart);

                // Imprimer le texte AVANT la balise QR
                if (!textBefore.isEmpty()) {
                    printer.addTextAlign(Printer.ALIGN_CENTER);
                    printer.addText(textBefore);
                }

                // Générer le QRCode à la place de la balise
                String qrContent = matcher.group(1);
                if (qrContent != null && !qrContent.isEmpty()) {
                    printer.addTextAlign(Printer.ALIGN_CENTER);
                    printer.addSymbol(qrContent, Printer.SYMBOL_QRCODE_MODEL_2,
                            Printer.LEVEL_L,
                            8, // size (3 to 16)
                            1, 0);
                }

                currentIndex = qrEnd; // Avancer l'index pour continuer après le QR
            }

            // Texte après le dernier QRCode
            if (currentIndex < textToPrint.length()) {
                String textAfter = textToPrint.substring(currentIndex);
                if (!textAfter.isEmpty()) {
                    printer.addTextAlign(Printer.ALIGN_CENTER);
                    printer.addText(textAfter + "\n");
                }
            }

            printer.addCut(Printer.CUT_FEED);

            printer.sendData(Printer.PARAM_DEFAULT);

            callbackContext.success("Impression envoyée !");

            printer.setReceiveEventListener(new ReceiveListener() {
                @Override
                public void onPtrReceive(Printer printer, int code, PrinterStatusInfo status, String printJobId) {
                    Log.d("EpsonPrinterPlugin", "Callback reçu, job terminé");
                    try {
                        printer.endTransaction();
                        printer.disconnect();
                    } catch (Epos2Exception e) {
                        callbackContext.error("Erreur impression : " + e.getErrorStatus());
                    }
                }
            });
        } catch (Epos2Exception e) {
            callbackContext.error("Erreur impression : " + e.getErrorStatus());
        }
    }

    private void isPrinterAvailable(CallbackContext callbackContext) {
        try {
            Printer printer = new Printer(Printer.TM_T88, Printer.MODEL_ANK, cordova.getActivity());
            printer.connect("USB:", Printer.PARAM_DEFAULT);
            printer.beginTransaction();

            PrinterStatusInfo status = printer.getStatus();

            boolean isOnline = status != null && status.getConnection() == Printer.TRUE && status.getOnline() == Printer.TRUE;

            printer.endTransaction();
            printer.disconnect();

            if (isOnline) {
                callbackContext.success("Imprimante disponible");
            } else {
                callbackContext.error("Imprimante non disponible");
            }

        } catch (Epos2Exception e) {
            callbackContext.error("Erreur vérif dispo : " + e.getErrorStatus());
        }
    }
}
