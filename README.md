# EpsonCordovaSDK

## Description

EpsonCordovaSDK est un plugin Cordova permettant d'interagir avec des imprimantes Epson, principalement les modèles TM-T88. Ce plugin permet d'imprimer du texte, vérifier la disponibilité de l'imprimante et gérer des fonctionnalités comme l'impression de QR codes et de texte en gras via des balises spécifiques.

### Fonctionnalités :

- **Impression de texte** : Permet d'envoyer un texte à l'imprimante avec prise en charge des styles (gras, QR codes).
- **Vérification de la disponibilité de l'imprimante** : Vérifie si l'imprimante est connectée et prête à l'emploi.

## Prérequis

- Un appareil mobile ou une plateforme capable de se connecter à une imprimante Epson via USB.
- La bibliothèque Epos2 SDK d'Epson pour la gestion de l'impression.

## Installation

### Ajouter le plugin

Pour ajouter le plugin à ton projet Cordova, utilise la commande suivante :

```bash
cordova plugin add https://github.com/eliberty/EpsonCordovaSDK.git
```

### Supprimer le plugin

Si tu veux retirer le plugin de ton projet, utilise cette commande :

```bash
cordova plugin rm com.eliberty.cordova.plugin.epsonusb
```

## Utilisation

### Code côté React

Voici un exemple de code pour appeler le plugin depuis une application React (ou toute autre application utilisant Cordova) :

```javascript
window.plugins.EpsonPrinter.printText(
  () => {
    console.info("** Elib **  Ticket imprimé avec succès");
  },
  (err) => {
    console.info("** Elib **  Erreur impression Epson : ", err);
  },
  { text: textToPrint }
);
```

### API

#### `printText(success, fail, options)`

Imprime un texte avec des balises spéciales pour le formatage (gras et QR codes).

- `success` : Fonction de callback appelée en cas de succès.
- `fail` : Fonction de callback appelée en cas d'échec.
- `options` : Objet contenant le texte à imprimer sous la forme `{ text: "Texte à imprimer" }`.

Exemple de texte à imprimer avec balises :

```plaintext
<BOLD>Texte en gras</BOLD>
<QRCODE>https://www.example.com</QRCODE>
Texte normal
```

#### `isPrinterAvailable(success, fail)`

Vérifie si l'imprimante est disponible.

- `success` : Fonction de callback appelée si l'imprimante est disponible.
- `fail` : Fonction de callback appelée si l'imprimante n'est pas disponible.

## Détails du fonctionnement

Le plugin utilise les API d'Epson pour gérer l'impression des tickets. Lors de l'appel de la fonction `printText`, le plugin :

1. Vérifie la connexion à l'imprimante.
2. Formate le texte pour l'impression en ajoutant des styles (gras, QR codes).
3. Envoie les données à l'imprimante pour imprimer le texte.

### Balises de formatage

- `<BOLD>` et `</BOLD>` : Texte en gras.
- `<QRCODE>` et `</QRCODE>` : Génération d'un QR code.

## Documentation officielle

Pour plus d'informations sur l'installation et l'utilisation des imprimantes Epson TM-T88, consulte les ressources suivantes :

- [Documentation d'installation des imprimantes Epson TM-T88](https://support.epson.net/setupnavi/?PINF=swlist&OSC=MI&LG2=FR&MKN=TM-T88VI)
- [Référence API Epson ePOS SDK](https://download4.epson.biz/sec_pubs/pos/reference_en/epos_and/ref_epos_sdk_and_en_printerclass_printer.html)

## Débogage

Le plugin envoie des messages dans les logs pour faciliter le débogage.
Tu peux connecter une application cordova à un device en USB avec cette commande `cordova run android --device --debug`
Puis tu peux consulter ces logs via `adb logcat` (`| grep Elib`) pour diagnostiquer les erreurs ou problèmes de connexion.

Exemple de log :

```plaintext
EpsonPrinterPlugin - Texte reçu pour impression : "Texte à imprimer"
EpsonPrinterPlugin - Avant instanciation Printer
EpsonPrinterPlugin - Printer instancié
```

## Problèmes connus

- Le model de l'imprimante est précisé dans le code : Printer.TM_T88 (TM-T88IV, TM-T88V, TM-T88VI, TM-T88V-i, TM-T88VI-iHUB, TM-T88V-DT, TM-T88VI-DT2)
- Les accents ne sont pas gérés. Il faut donc normaliser le texte avant de l'envoyer à l'api :

```javascript
ticketText.normalize("NFD").replace(/[\u0300-\u036f]/g, "");
```

- Il n'y a pas de fonctionnalité pour sauter des lignes sans cassures sur les mots.
  Dans ce cas, il faut le gerer soit même. Exemple :

```javascript
const wrapText = (text, maxChars) => {
  const lines = text.split("\n");
  const wrappedLines = [];

  for (const line of lines) {
    if (line.length <= maxChars) {
      wrappedLines.push(line);
    } else {
      const words = line.split(/\s+/);
      let currentLine = "";

      for (const word of words) {
        if ((currentLine + " " + word).trim().length <= maxChars) {
          currentLine += (currentLine ? " " : "") + word;
        } else {
          wrappedLines.push(currentLine);
          currentLine = word;
        }
      }

      if (currentLine) wrappedLines.push(currentLine);
    }
  }

  return wrappedLines.join("\n");
};

ticketText = wrapText(ticketText, 42); // <= nombre de caractères max
```
