# Keep the AccessibilityService — référence dans AndroidManifest, sinon ProGuard peut le supprimer en release.
-keep class fr.acart.tbridge.TalkBackGestureService { *; }
