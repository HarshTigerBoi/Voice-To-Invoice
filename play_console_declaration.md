# Play Console Sensitive Permission Declaration Form
## Permission: `BIND_NOTIFICATION_LISTENER_SERVICE`

### 1. Primary Feature Declaration
**Selected Feature Category:** Financial Management / Ledger Reconciliation

### 2. Core Use Case Justification (Text to copy-paste into Play Console)
> "Our app, Voice-First Shop Ledger, is an offline-first micro-ledger designed for small Kirana and Vegetable shopkeepers in India to manage sales, daily produce pricing, and customer credit (Udhaar). 
> 
> The `BIND_NOTIFICATION_LISTENER_SERVICE` permission is exclusively used to capture passive payment notifications emitted by Indian UPI payment apps (Paytm, PhonePe, Google Pay). When a customer scans the shopkeeper's QR code and pays, the app reads the transaction amount from the notification to automatically match it with pending sales and log the payment without requiring manual data entry during busy shop hours.
> 
> **Data Privacy & Bounds:**
> 1. Only notifications originating from payment apps (Paytm, PhonePe, Google Pay) are processed.
> 2. Only the numerical transaction amount and payment status are extracted.
> 3. All non-payment notifications (personal SMS, chat alerts) are ignored immediately and never logged, stored, or transmitted.
> 4. An explicit in-app prominent disclosure dialog is presented to the user before prompting them to enable Notification Access."

### 3. Video Proof Checklist for Submission
- Video showing the shopkeeper opening the app.
- Clear view of the in-app prominent disclosure dialog explaining why Notification Access is required.
- Screen recording showing the user navigating to system settings and granting access.
- Demonstration of a simulated Paytm/PhonePe payment notification triggering the automatic payment entry in the app ledger.
