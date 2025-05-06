(function (cordova) {
  var EpsonPrinter = function () {};

  EpsonPrinter.prototype.printText = function (success, fail, options) {
    return cordova.exec(
      function (args) {
        success(args);
      },
      function (args) {
        fail(args);
      },
      "EpsonPrinterPlugin",
      "printText",
      [options]
    );
  };

  EpsonPrinter.prototype.isPrinterAvailable = function (success, fail) {
    return cordova.exec(
      function (args) {
        success(args);
      },
      function (args) {
        fail(args);
      },
      "EpsonPrinterPlugin",
      "isPrinterAvailable",
      []
    );
  };

  window.EpsonPrinter = new EpsonPrinter();

  // rétrocompatibilité éventuelle
  window.plugins = window.plugins || {};
  window.plugins.EpsonPrinter = window.EpsonPrinter;
})(window.PhoneGap || window.Cordova || window.cordova);
