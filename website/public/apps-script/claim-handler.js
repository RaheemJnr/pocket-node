// Google Apps Script — CKB Meetup Claim Handler
// Deploy: Extensions > Apps Script > Deploy > Web App
// Execute as: Me, Access: Anyone

var SHEET_ID = '1ufRJqM36eKOPc5YmQNtFbhAzhdJ1o19XFV5_IHzhBEM';

function doPost(e) {
  // Use LockService to prevent concurrent write issues
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000); // Wait up to 10 seconds
  } catch (err) {
    return jsonResponse({ success: false, error: 'Server busy. Please try again in a moment.' });
  }

  try {
    var body = JSON.parse(e.postData.contents);
    var email = (body.email || '').trim().toLowerCase();
    var address = (body.address || '').trim();

    // Validate inputs
    if (!email || email.indexOf('@') === -1) {
      return jsonResponse({ success: false, error: 'Please enter a valid email address.' });
    }
    // Validate CKB address: ckb1 prefix + Bech32 character set only
    var bech32Regex = /^ckb1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{42,}$/;
    if (!address || !bech32Regex.test(address)) {
      return jsonResponse({ success: false, error: 'Please enter a valid CKB mainnet address (starts with ckb1).' });
    }

    var sheet = SpreadsheetApp.openById(SHEET_ID).getSheets()[0];
    var data = sheet.getDataRange().getValues();
    var headers = data[0];

    // Find columns by header name
    var emailCol = findColumn(headers, 'Email');
    var checkedInCol = findColumn(headers, 'Checked In');
    var addressCol = findColumn(headers, 'CKB Address');
    var claimedAtCol = findColumn(headers, 'Claimed At');

    if (emailCol === -1) {
      return jsonResponse({ success: false, error: 'Configuration error: Email column not found.' });
    }

    // REQUIRED: Columns must be pre-added to the sheet (Task 4 Step 1).
    // This check is a safety fallback only — do NOT rely on auto-create in production
    // because concurrent requests could race on column creation.
    if (addressCol === -1) {
      addressCol = headers.length;
      sheet.getRange(1, addressCol + 1).setValue('CKB Address');
    }
    if (claimedAtCol === -1) {
      claimedAtCol = addressCol + 1;
      sheet.getRange(1, claimedAtCol + 1).setValue('Claimed At');
    }

    // Determine if check-in gate is active
    var checkInActive = false;
    if (checkedInCol !== -1) {
      for (var i = 1; i < data.length; i++) {
        if (data[i][checkedInCol] && data[i][checkedInCol].toString().trim() !== '') {
          checkInActive = true;
          break;
        }
      }
    }
    if (!checkInActive) {
      Logger.log('CONTINGENCY_MODE: no check-in data found, accepting registration-only');
    }

    // Find matching row
    var matchRow = -1;
    for (var i = 1; i < data.length; i++) {
      var rowEmail = (data[i][emailCol] || '').toString().trim().toLowerCase();
      if (rowEmail === email) {
        matchRow = i;
        break;
      }
    }

    if (matchRow === -1) {
      return jsonResponse({ success: false, error: 'Email not registered for this event.' });
    }

    // Check attendance if gate is active
    if (checkInActive && checkedInCol !== -1) {
      var checkedIn = data[matchRow][checkedInCol];
      if (!checkedIn || checkedIn.toString().trim() === '' || checkedIn === false) {
        return jsonResponse({ success: false, error: 'You must be checked in at the event to claim.' });
      }
    }

    // Check if already claimed
    // Re-read to avoid stale data for the specific row
    var currentAddress = sheet.getRange(matchRow + 1, addressCol + 1).getValue();
    if (currentAddress && currentAddress.toString().trim() !== '') {
      return jsonResponse({ success: false, error: 'This email has already claimed CKB.' });
    }

    // Write claim
    sheet.getRange(matchRow + 1, addressCol + 1).setValue(address);
    sheet.getRange(matchRow + 1, claimedAtCol + 1).setValue(new Date().toISOString());

    return jsonResponse({ success: true });

  } catch (err) {
    Logger.log('Error: ' + err.toString());
    return jsonResponse({ success: false, error: 'Something went wrong. Please try again.' });
  } finally {
    lock.releaseLock();
  }
}

function findColumn(headers, name) {
  var lowerName = name.toLowerCase();
  for (var i = 0; i < headers.length; i++) {
    if (headers[i].toString().trim().toLowerCase() === lowerName) {
      return i;
    }
  }
  return -1;
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

// For testing in Apps Script editor
function doGet(e) {
  return jsonResponse({ status: 'Claim endpoint active. Use POST to submit.' });
}
