# Test a Trio connection with a v4 Virtual master

This mode tests the normal Trio encrypted command payload using a Nightscout test mailbox. It does not use Apple push, test APNs keys, or run any pump or treatment action. The receiver records a decoded action and returns an encrypted confirmation.

Use a separate test Nightscout site. Both devices need NSClientV3 connected to that site and tokens allowed to read and write settings. The receiver also needs settings-history read permission (`api:settings:admin`). A test-site admin token can provide these permissions. No AAPS pairing is required for this transport.

On the receiving full v4 app:

1. Select VirtualPump. A real pump or a client build cannot run the receiver.
2. Open Settings > NSClientV3 > Trio connection simulator.
3. Enter a Simulator ID, such as `virtual-test`, and a Simulator shared secret.
4. Turn on Receive simulated Trio commands. Leave Send to the Trio simulator off on this device.
5. Keep NSClientV3 running. Simulator status should say it is ready. The last received action is shown in this same read-only status field.

On the sending v4 app or client:

1. Under Settings > NSClientV3 > Trio connection simulator, enter exactly the same ID and simulator secret.
2. Turn on Send to the Trio simulator. Leave Receive simulated Trio commands off.
3. Under Settings > NSClientV3 > Trio remote control, turn on Trio remote control and enter a User name. APNs credentials and the real Trio shared secret are not used in simulator mode.
4. Open Manage > Trio remote control. Check the SIMULATOR banner before confirming a command.
5. Try a temp target, meal, bolus, or the sample override. A successful test says Simulator confirmed. On the receiver, inspect Simulator status / last received action. Nothing is entered into bolus, carbs, target, or profile history.

The receiver rejects invalid or expired commands and remembers accepted commands across restarts so replaying one does not record it again. A timeout means confirmation was not received; the test message can still be pending. The receiver must have run within the last 30 seconds before the sender uploads a command.

To stop the test, turn off the receiver and sender switches. To return the sender to real Trio, turn off Send to the Trio simulator and configure the real Trio shared secret and APNs keys. Reopen the remote screen before confirming. A change between simulator and real mode requires a new confirmation.

Simulator records use settings identifiers beginning `aaps-trio-sim-`. They are separate from profiles, treatments and signed AAPS client-control documents. This implementation does not receive real Apple notifications; full APNs testing still needs a compatible iOS receiver.
