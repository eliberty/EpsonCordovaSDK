package com.eliberty.cordova.plugin.epsonusb;

import com.epson.epos2.printer.Printer;
import com.epson.epos2.Epos2Exception;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbDeviceConnection;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;

public class EpsonPrinterPlugin extends CordovaPlugin {

    private static final int EPSON_VENDOR_ID = 0x04B8;
    private static final int EPSON_PRODUCT_ID = 0x0202;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("printText")) {
            this.printText(callbackContext);
            return true;
        }
        return false;
    }

    private void printText(CallbackContext callbackContext) {
        try {
            Printer printer = new Printer(Printer.TM_T88, Printer.MODEL_ANK, cordova.getActivity());
            printer.addText("Hello depuis Cordova !\n");
            printer.addCut(Printer.CUT_FEED);
            printer.connect("USB:", Printer.PARAM_DEFAULT);
            printer.beginTransaction();
            printer.sendData(Printer.PARAM_DEFAULT);
            printer.endTransaction();
            printer.disconnect();
            callbackContext.success("Impression envoyée !");
        } catch (Epos2Exception e) {
            callbackContext.error("Erreur impression : " + e.getErrorStatus());
        }
    }
}
