# Keyboard Autofill Library

Easy-to-integrate form autofill functionality for Android custom keyboards.

[![](https://jitpack.io/v/yourusername/keyboard-autofill-library.svg)](https://jitpack.io/#yourusername/keyboard-autofill-library)

## Features

- 🎯 **Smart Field Detection** - Automatically detects email, name, phone, address fields using hint text and input types
- 📚 **Learning System** - Learns from user input and improves suggestions over time
- ⚡ **Fast Performance** - LRU caching and optimized data structures for real-time suggestions
- 🎨 **Clean UI** - Horizontal suggestion bar that fits seamlessly into any keyboard design
- 📱 **Memory Efficient** - Designed for mobile constraints with automatic cleanup
- 🔄 **Cross-App Learning** - Suggestions work across all apps on the device

## Demo

![Autofill Demo](demo.gif)

## Prerequisites

Your project must have:
- ✅ A custom keyboard extending `InputMethodService`
- ✅ Existing `onCreateInputView()`, `onStartInput()`, `onKey()` methods
- ✅ Minimum SDK 21+
- ✅ Kotlin support

## Quick Integration

### Step 1: Add Dependency

**Method A: Git Submodule (Recommended for development)**
```bash
git submodule add https://github.com/yourusername/keyboard-autofill-library.git keyboard-autofill
```

Then add to your project's `settings.gradle.kts`:
```kotlin
include(":app")
include(":keyboard-autofill")  // Add this line
```

And in your app's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":keyboard-autofill"))
    // ... your existing dependencies
}
```

**Method B: JitPack (Simpler for end users)**
```kotlin
// In your project's build.gradle.kts or settings.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

// In your app's build.gradle.kts
dependencies {
    implementation("com.github.yourusername:keyboard-autofill-library:v1.0.0")
}
```

### Step 2: Add Suggestion Bar to Your Keyboard Layout

Add this RecyclerView to your keyboard's main layout XML **above your existing keyboard view**:

```xml
<!-- Add this ABOVE your existing keyboard view -->
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/suggestion_bar"
    android:layout_width="match_parent"
    android:layout_height="40dp"
    android:layout_above="@id/your_existing_keyboard_view"
    android:background="#FF1A1A1A"
    android:visibility="gone"
    android:paddingStart="8dp"
    android:paddingEnd="8dp"
    android:clipToPadding="false" />

<!-- Your existing keyboard view stays here -->
<your.package.CustomKeyboardView
    android:id="@+id/your_existing_keyboard_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_alignParentBottom="true" />
```

**Important Layout Notes:**
- The suggestion bar must have `android:id="@+id/suggestion_bar"` (exact ID required)
- Position it above your keyboard using `android:layout_above="@id/your_keyboard_view"`
- Keep `android:visibility="gone"` - the library will show/hide it automatically

### Step 3: Integrate in Your InputMethodService

**Add import and field:**
```java
import com.keyboardautofill.AutofillIntegration;

public class YourKeyboard extends InputMethodService 
    implements KeyboardView.OnKeyboardActionListener {
    
    private AutofillIntegration autofillIntegration;  // Add this field
    
    // ... your existing fields ...
}
```

**Initialize in onCreateInputView():**
```java
@Override
public View onCreateInputView() {
    // Your existing layout inflation
    View keyboardView = getLayoutInflater().inflate(R.layout.your_keyboard_layout, null);
    
    // Your existing keyboard setup code here...
    // (setting up keyboard views, listeners, etc.)
    
    // ADD THIS: Initialize autofill integration
    autofillIntegration = AutofillIntegration
        .create(this, keyboardView)
        .initialize();
        
    return keyboardView;
}
```

**Add field focus detection in onStartInput():**
```java
@Override
public void onStartInput(EditorInfo attribute, boolean restarting) {
    super.onStartInput(attribute, restarting);
    
    // Your existing onStartInput code here...
    // (setting up keyboard type, prediction settings, etc.)
    
    // ADD THIS: Notify autofill of field focus
    if (autofillIntegration != null) {
        autofillIntegration.onFieldFocused(attribute);
    }
}
```

**Add field focus detection in onStartInputView() (if you have this method):**
```java
@Override
public void onStartInputView(EditorInfo attribute, boolean restarting) {
    super.onStartInputView(attribute, restarting);
    
    // Your existing code...
    
    // ADD THIS: Ensure autofill knows about field focus
    if (autofillIntegration != null) {
        autofillIntegration.onFieldFocused(attribute);
    }
}
```

**Notify on typing in onKey():**
```java
@Override
public void onKey(int primaryCode, int[] keyCodes) {
    // Your existing key processing logic here...
    // (handling character input, backspace, etc.)
    
    // Keep all your existing key processing code!
    
    // ADD THIS AT THE END: Notify autofill of content changes
    if (autofillIntegration != null) {
        autofillIntegration.onFieldChanged();
    }
}
```

**Save data when keyboard closes:**
```java
@Override
public void onFinishInput() {
    // ADD THIS FIRST: Save field data before cleanup
    if (autofillIntegration != null) {
        autofillIntegration.onKeyboardHidden();
    }
    
    // Your existing cleanup code here...
    super.onFinishInput();
}

@Override
public void onFinishInputView(boolean finishingInput) {
    // ADD THIS: Save data when view is hidden
    if (autofillIntegration != null) {
        autofillIntegration.onKeyboardHidden();
    }
    
    // Your existing code...
    super.onFinishInputView(finishingInput);
}
```

### Step 4: Build and Test

```bash
./gradlew clean
./gradlew build
./gradlew installDebug
```

## That's It! 🎉

Your keyboard now has intelligent autofill that:
- ✅ **Detects field types** automatically (email, name, phone, etc.)
- ✅ **Shows suggestions** when users focus on form fields
- ✅ **Learns from usage** - suggestions improve with user interaction
- ✅ **Works cross-app** - data entered in one app suggests in others
- ✅ **Handles user selection** - clicked suggestions boost ranking for future use

## How It Works

### Field Detection
The library analyzes `EditorInfo` from Android's input system:
1. **Hint text analysis** - "email", "first name", "phone" keywords
2. **Input type checking** - `TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, etc.
3. **Package context** - App-specific patterns for better accuracy

### Learning System
- **Manual typing**: When users type and move to next field, data is learned
- **Suggestion selection**: When users click suggestions, ranking increases
- **Frequency + Recency**: Algorithms balance how often and how recently data was used

### UI Integration
- Suggestion bar appears automatically for detected form fields
- Horizontal scrollable list with touch-friendly design
- Matches your keyboard's visual style
- Shows/hides based on field type and available suggestions

## Supported Field Types

| Field Type | Detection Keywords | Input Types |
|------------|-------------------|-------------|
| First Name | "first", "given" + "name" | `TYPE_TEXT_VARIATION_PERSON_NAME` |
| Last Name | "last", "family" + "name" | `TYPE_TEXT_VARIATION_PERSON_NAME` |
| Full Name | "full" + "name" | `TYPE_TEXT_VARIATION_PERSON_NAME` |
| Email | "email", "e-mail" | `TYPE_TEXT_VARIATION_EMAIL_ADDRESS` |
| Phone | "phone", "mobile", "cell" | `TYPE_CLASS_PHONE` |
| Address | "address", "street" | `TYPE_TEXT_VARIATION_POSTAL_ADDRESS` |
| City | "city", "town" | Text analysis |
| State | "state", "province" | Text analysis |
| ZIP | "zip", "postal" | Text analysis |
| Company | "company", "organization" | Text analysis |
| Username | "username", "user" | Text analysis |

## API Reference

### AutofillIntegration

Main integration class that coordinates all autofill functionality.

#### Static Methods

**`create(inputMethodService, keyboardRootView)`**
- **Parameters**: 
  - `inputMethodService`: Your `InputMethodService` instance
  - `keyboardRootView`: Root view returned from `onCreateInputView()`
- **Returns**: `AutofillIntegration` instance
- **Usage**: Call in `onCreateInputView()` after layout inflation

**`initialize()`**
- **Returns**: Same `AutofillIntegration` instance (for chaining)
- **Usage**: Chain after `create()` to complete setup

#### Instance Methods

**`onFieldFocused(editorInfo)`**
- **Parameters**: `EditorInfo` from `onStartInput()` or `onStartInputView()`
- **Purpose**: Detects field type and shows appropriate suggestions
- **Usage**: Call whenever user focuses on a new input field

**`onFieldChanged()`**
- **Purpose**: Updates internal tracking of field content changes
- **Usage**: Call in `onKey()` after processing user input

**`onKeyboardHidden()`**
- **Purpose**: Saves current field data for future suggestions
- **Usage**: Call in `onFinishInput()` and `onFinishInputView()`

**`getCurrentFieldType()`**
- **Returns**: String name of detected field type
- **Usage**: Debugging and logging

**`hasCurrentSuggestions()`**
- **Returns**: Boolean indicating if suggestions are available
- **Usage**: Optional - check if current field has suggestion data

## Customization

### Visual Styling

Override these dimensions in your `values/dimens.xml`:
```xml
<dimen name="suggestion_text_size">13sp</dimen>
<dimen name="suggestion_padding_horizontal">12dp</dimen>
<dimen name="suggestion_padding_vertical">6dp</dimen>
<dimen name="suggestion_margin">4dp</dimen>
```

### Suggestion Bar Background

The library uses your keyboard's background by default. To customize:
```xml
<!-- In your keyboard layout -->
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/suggestion_bar"
    android:background="#FF1A1A1A"  <!-- Custom background -->
    ... />
```

### Individual Suggestion Styling

Suggestions use these default colors:
- **Background**: `#FF404040` (dark gray)
- **Border**: `#FFFFFFFF` (white)
- **Text**: White
- **Pressed state**: `#FF505050` (lighter gray)

## Troubleshooting

### Suggestion Bar Not Appearing
1. ✅ Check `android:id="@+id/suggestion_bar"` is exact
2. ✅ Verify `AutofillIntegration.create()` is called with correct root view
3. ✅ Test with known form fields (email, name fields)
4. ✅ Check logs for "SuggestionDebug" entries

### No Suggestions Showing
1. ✅ Enter some test data first, then return to the field
2. ✅ Verify `onFieldChanged()` is called during typing
3. ✅ Check `onKeyboardHidden()` is called when switching fields

### Build Errors
1. ✅ Ensure RecyclerView dependency is included
2. ✅ Check Kotlin support is enabled
3. ✅ Verify minimum SDK is 21+

### Integration Issues
1. ✅ Make sure your keyboard extends `InputMethodService`
2. ✅ Verify you have the required lifecycle methods
3. ✅ Check import statement: `import com.keyboardautofill.AutofillIntegration;`

## Example Project

- **Complete Integration**: [JwollBoard](https://github.com/Jwoll2004/jwoll-board) - Full custom keyboard with autofill

## Requirements

- **Android API 21+** (Android 5.0 Lollipop)
- **Kotlin support** in your project
- **RecyclerView** (automatically included as dependency)
- **Custom keyboard** extending `InputMethodService`
