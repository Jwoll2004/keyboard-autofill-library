# Keyboard Autofill Library

Easy-to-integrate form autofill functionality for Android custom keyboards.

[![](https://jitpack.io/v/yourusername/keyboard-autofill-library.svg)](https://jitpack.io/#yourusername/keyboard-autofill-library)

## Features

- 🎯 **Smart Field Detection** - Automatically detects email, name, phone, address fields
- 📚 **Learning System** - Learns from user input and improves suggestions over time
- ⚡ **Fast Performance** - LRU caching and optimized data structures
- 🎨 **Clean UI** - Horizontal suggestion bar that fits any keyboard design
- 📱 **Memory Efficient** - Designed for mobile constraints

## Demo

![Autofill Demo](demo.gif)

## Quick Integration

### Step 1: Add Dependency

**Method A: JitPack (Recommended)**
```kotlin
// In your project's settings.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

// In your app's build.gradle.kts
dependencies {
    implementation("com.github.yourusername:keyboard-autofill-library:1.0.0")
}
```

**Method B: Git Submodule**
```bash
git submodule add https://github.com/yourusername/keyboard-autofill-library.git keyboard-autofill
```
Then add to `settings.gradle.kts`:
```kotlin
include(":keyboard-autofill")
```

### Step 2: Add Suggestion Bar to Layout

Add this RecyclerView to your keyboard layout XML:
```xml
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/suggestion_bar"
    android:layout_width="match_parent"
    android:layout_height="40dp"
    android:layout_above="@id/your_keyboard_view"
    android:background="#FF2C2C2C"
    android:visibility="gone" />
```

### Step 3: Initialize in Your InputMethodService

```java
import com.keyboardautofill.AutofillIntegration;

public class YourKeyboard extends InputMethodService {
    
    private AutofillIntegration autofillIntegration;
    
    @Override
    public View onCreateInputView() {
        View keyboardView = // ... create your keyboard layout
        
        // Initialize autofill
        autofillIntegration = AutofillIntegration
            .create(this, keyboardView)
            .initialize();
            
        return keyboardView;
    }
    
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        
        // Notify field focus
        if (autofillIntegration != null) {
            autofillIntegration.onFieldFocused(attribute);
        }
    }
    
    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        // ... your key processing logic ...
        
        // Notify content change
        if (autofillIntegration != null) {
            autofillIntegration.onFieldChanged();
        }
    }
    
    @Override
    public void onFinishInput() {
        // Save field data
        if (autofillIntegration != null) {
            autofillIntegration.onKeyboardHidden();
        }
        
        super.onFinishInput();
    }
}
```

## That's It! 🎉

Your keyboard now has intelligent autofill suggestions that:
- Appear automatically when users focus on form fields
- Learn from user input patterns
- Provide one-tap completion for common fields
- Work across all apps

## Example Project

See the complete integration example: [JwollBoard](https://github.com/yourusername/JwollBoard)

## Supported Field Types

- First Name, Last Name, Full Name
- Email Address
- Phone Number
- Street Address, City, State, ZIP
- Company Name, Username

## API Reference

### AutofillIntegration

Main integration class for keyboard developers.

#### Methods

**`create(inputMethodService, keyboardRootView)`**
- Creates integration instance
- Call in `onCreateInputView()`

**`initialize()`**
- Initializes the autofill system
- Chain after `create()`

**`onFieldFocused(editorInfo)`**
- Notifies field focus change
- Call in `onStartInput()`

**`onFieldChanged()`**
- Notifies field content change
- Call in `onKey()` after processing

**`onKeyboardHidden()`**
- Saves current field data
- Call in `onFinishInput()`

## Customization

### Suggestion Bar Styling

Override these dimensions in your `values/dimens.xml`:
```xml
<dimen name="suggestion_text_size">13sp</dimen>
<dimen name="suggestion_padding_horizontal">12dp</dimen>
<dimen name="suggestion_padding_vertical">6dp</dimen>
<dimen name="suggestion_margin">4dp</dimen>
```

### Colors

The suggestion bar uses your keyboard's background. Individual suggestions use:
- Background: `#FF404040`
- Border: `#FFFFFFFF` 
- Text: White
- Pressed: `#FF505050`

## Requirements

- Android API 21+
- Kotlin support
- RecyclerView dependency (automatically included)

## Contributing

1. Fork the repository
2. Create feature branch
3. Add tests for new functionality
4. Submit pull request

## License

MIT License - see [LICENSE](LICENSE) for details

## Support

- 📚 [Documentation](https://github.com/yourusername/keyboard-autofill-library/wiki)
- 🐛 [Issues](https://github.com/yourusername/keyboard-autofill-library/issues)
- 💬 [Discussions](https://github.com/yourusername/keyboard-autofill-library/discussions)
