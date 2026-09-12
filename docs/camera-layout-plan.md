# Reference camera layout

Implement the user-approved reference with native Android and Compose controls.
The preview occupies the area between black system-bar framing and a black recording footer.
The preview has a menu, actual quality badge, latest-recording thumbnail, optional thirds grid, optional sensor level, zoom control, and camera flip control.
The footer centers an outlined white record button and leaves both sides empty for future controls.
Starting and finalizing states disable incompatible actions.

CameraRecorder owns camera capability, quality, and zoom state.
CameraScreen binds the preview and coordinates presentation.
Small components own controls, settings, thumbnail loading, and the sensor level.
Settings persist on the device.
The gallery continues to use the existing saved-video library.

Validate capability and state on the connected phone, update UI selectors for accessible icon controls, run local tests and lint, and inspect screenshots before delivery.
Build test APKs and run direct instrumentation with reinstall-in-place.
Do not use the connected Gradle test runner or uninstall the target application.
