# Native accessibility and window resources

The finite resources in res/values*/native_platform_resources.xml preserve the exact localized values and platform window attributes used by released Reverb, extracted from AndroidX Compose UI1.11.2 and foundation1.11.2 AAR resources. These are resource values only; no Compose code or dependency is present. Upstream AndroidX is Apache2.0 licensed; LICENSE.txt is retained.

UI resources: in_progress, state_on, state_off, selected, not_selected, switch_role, tab, default_popup_window_title, template_percent, close_sheet, default_error_message, state_empty, FloatingDialogWindowTheme, accessibility_custom_action_0 and1. Foundation resource: tooltip_label. All available locale variants were retained; no hand translation or color/layout substitution.

Settings radio selection adds only `selected` and `not_selected`, including all 86 supplied resource configurations, from UI1.11.2 `ui.aar` SHA-256 `a0d7f930af2ca775ce5e0e55c83f28a36c3b9b35ee0475190c3ff062f6962572`.
