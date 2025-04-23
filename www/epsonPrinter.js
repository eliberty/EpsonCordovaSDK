(function(cordova) {
    var EpsonPrinter = function () {};

    EpsonPrinter.prototype.printText = function (success, fail) {
        return cordova.exec(
            function (args) {
                success(args);
            },
            function (args) {
                fail(args);
            },
            'EpsonPrinterPlugin', // <--- nom exact de ta classe Java
            'printText',          // <--- méthode dans ta classe Java
            []
        );
    };

    window.EpsonPrinter = new EpsonPrinter();

    // rétrocompatibilité éventuelle
    window.plugins = window.plugins || {};
    window.plugins.EpsonPrinter = window.EpsonPrinter;

})(window.PhoneGap || window.Cordova || window.cordova);