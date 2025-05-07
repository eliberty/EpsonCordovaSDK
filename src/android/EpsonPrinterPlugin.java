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
                    printer.addTextStyle(Printer.TRUE, Printer.FALSE, Printer.FALSE, Printer.COLOR_1);
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
