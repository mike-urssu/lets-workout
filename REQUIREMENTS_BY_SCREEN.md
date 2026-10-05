# Requirements by Screen — Let's Workout

## Screen 1: Home / Workout Dashboard

**Purpose:** Display the user's current workout session (in progress or summary), key metrics, and quick access to recent exercises.

### Components
- **Status Bar**: Shows time, signal, battery (system-level, not our control)
- **Header**: "WORKOUT" title + date indicator
- **Timer Section**: Large display of elapsed time (HH:MM:SS format)
- **Key Metrics Cards**: 
  - Total calories burned
  - Total weight lifted (kg)
  - Repetitions count
  - Sets count
- **Exercise List**: Scrollable list of exercises in current session (or recent exercises if not in session)
  - Exercise card showing: name, category, weight, reps
  - Edit/Delete buttons on each card
- **Action Buttons**: 
  - [Start Workout]
  - [Pause] (if in progress)
  - [End Workout]
- **Bottom Navigation**: 5 tabs (Home, Records, Statistics, Gallery, Settings)

### User Actions
- **[Start Workout]**: Begins a new session. What is the initial state? Empty exercise list? Can user set a name for the session?
- **[Pause]**: Stops the timer. Session continues in paused state. Can user resume?
- **[End Workout]**: Saves the session with final totals (calories, weight). Redirects to session summary? Or stays on home screen?
- **[Edit Exercise Card]**: Opens exercise detail screen to modify weights/reps/notes
- **[Delete Exercise Card]**: Removes exercise from session. Can this be undone?
- **[Tap Exercise Card]**: Shows exercise details (full breakdown of sets)?
- **[Add Exercise Button]** (implicit): How does user add new exercise to active session? Floating button? Menu?
- **[Navigate Tab]**: Switches to Records, Statistics, Gallery, or Settings screen

### Data Model

**Inputs:**
- None on initial load (data comes from backend)

**Outputs/Display:**
- `sessionId` (number): ID of current/active session
- `elapsedTime` (duration): Calculated from `startTime` and current time
- `exercises` (array of Exercise):
  - `id`, `name`, `category`, `weight`, `reps`, `sets`
- `totalCalories` (number): Sum of all exercises
- `totalWeight` (number): Sum of all weights
- `totalReps` (number): Sum of all repetitions

**State:**
- `sessionStatus`: "idle" (no active session) → "in_progress" (timer running) → "paused" (timer stopped) → "completed" (session saved)
- `timerRunning`: boolean
- `exercises`: [] initially, populated as user adds exercises

### Edge Cases & Errors
- **Empty state**: No active session yet. Show "Start your first workout" with prominent [Start] button.
- **No exercises added yet**: Timer running but no exercises. Show "Add an exercise to get started" prompt.
- **Long exercise list**: Scrollable? Paginated? How many exercises show before scroll?
- **Concurrent modifications**: User on phone and on web at same time. Which takes precedence? Last-write-wins or conflict warning?
- **Timer accuracy**: Does timer continue if app backgrounded? For how long?
- **Negative time**: If user deletes `startTime` by accident, can elapsed time be negative? Should it reset to 0?
- **Session timeout**: If user leaves app open for 24+ hours, does session auto-save or expire?
- **Network loss during session**: Does session persist locally? Sync when online?

### Unresolved Questions ❓
- [ ] What data is shown on the home screen if there is NO active session? Previous session summary? Empty state?
- [ ] Can user have multiple concurrent sessions (e.g., one on phone, one on web)?
- [ ] Is the timer stopped by [Pause], or just paused and resumable?
- [ ] When user taps [End Workout], what's the next screen? Session review? Home screen? Statistics?
- [ ] Can exercises be reordered? Drag-to-reorder or fixed sequence?
- [ ] Should the app prevent leaving the home screen with an active session in progress (warn user)?
- [ ] Are `totalCalories` calculated or user-entered? If user-entered, when does user input this?

---

## Screen 2: Exercise Detail / Edit

**Purpose:** View and edit a specific exercise within a session. Show all sets, allow adding/editing/deleting sets.

### Components
- **Header**: Exercise name + category (e.g., "Bench Press - Chest")
- **Sets List**: Vertically scrollable list of sets
  - Each set card shows: set #, weight (kg), reps, rest time
  - Edit icon on each set
  - Delete icon on each set
- **Add Set Button**: [+ Add Set]
- **Metrics Summary**:
  - Total weight for this exercise
  - Total reps for this exercise
  - Average reps per set
- **Actions**:
  - [Save & Back] / [Back to Workout]
  - [Delete Exercise]

### User Actions
- **[Edit Set Card]**: Opens inline editor or modal to change weight/reps/rest time
- **[Delete Set Card]**: Removes set from exercise. Confirmation dialog? Or undo option?
- **[+ Add Set]**: Appends new empty set. Auto-focus on weight field?
- **[Save & Back]**: Persists exercise changes and returns to workout screen
- **[Delete Exercise]**: Removes entire exercise from session (and its associated media). Confirmation required?

### Data Model

**Inputs:**
- `exerciseId` (number): Which exercise to edit
- `sessionId` (number): Which session it belongs to

**Outputs/Display:**
- `exercise` (object):
  - `id`, `name`, `category`
- `sets` (array):
  - `id`, `weightKg`, `reps`, `restSeconds`
- `totalWeight` (calculated)
- `totalReps` (calculated)

**State:**
- `isEditing`: boolean (user actively modifying sets?)
- `unsavedChanges`: boolean (has user changed anything since load?)
- `selectedSet`: which set is being edited?

### Edge Cases & Errors
- **Empty sets list**: Exercise with no sets (user added exercise but hasn't logged any sets yet). Show prompt "Add a set to record your work."
- **Duplicate set**: User adds identical set twice (same weight/reps). Should it warn or allow?
- **Very heavy weight**: User enters 9999 kg. Validation? Just allow?
- **Zero reps**: User enters 0 reps. Valid? Or require ≥1?
- **Negative rest time**: If user manually enters -60 seconds, prevent or allow?
- **Modified while editing**: User is editing Set 1 while backend deletes Set 2. Conflict? Refresh?
- **Unsaved changes on back**: User edits sets but presses back without saving. Warn or auto-save?
- **Very long exercise list**: 50 sets in one exercise. Performance? Virtualization?

### Unresolved Questions ❓
- [ ] Can user edit the exercise name/category here, or only on a different screen?
- [ ] Should resting time be tracked (user taps when rest ends)?
- [ ] Can user add notes/comments to a set (e.g., "felt stronger today")?
- [ ] Is there a "copy previous set" shortcut (auto-fill last set's weight/reps)?
- [ ] Can user see previous sessions' data for this exercise (to compare progress)?
- [ ] Does "Delete Exercise" also delete associated media files?
- [ ] Can user add media (photo/video) to individual sets, or only to the exercise?

---

## Screen 3: Statistics / Analytics

**Purpose:** Display aggregated workout data over time with visual charts and summary metrics.

### Components
- **Period Selector**: Toggle buttons or dropdown
  - [Week] [Month] [Year]
- **Summary Cards**: (showing for selected period)
  - Total sessions count
  - Total calories
  - Total weight lifted
  - Average intensity
  - Total workout duration
- **Chart**: Line/area graph showing trend over time
  - X-axis: dates (or weeks/months depending on period)
  - Y-axis: values (calories? weight? sessions?)
  - Multiple lines if comparing metrics
  - Legend showing which line is which
  - Hover/tap for exact values
- **Breakdown by Exercise** (optional): 
  - Which exercises did user do most?
  - Which shows most progress?

### User Actions
- **[Week] / [Month] / [Year]**: Changes aggregation period. Chart and cards update.
- **[Tap Chart Point]**: Shows tooltip with exact values for that date?
- **[Swipe Chart]**: Scroll horizontally to see different date ranges?
- **[Export Report]**: Does user want to export data as CSV/PDF?

### Data Model

**Inputs:**
- `periodType`: "WEEK" | "MONTH" | "YEAR"
- `userId`: Implicit (current user's data)

**Outputs/Display:**
- `period`: string (e.g., "Jan 15 - Jan 21, 2024")
- `summary` (object):
  - `totalSessions` (number)
  - `totalCalories` (number)
  - `totalWeight` (number)
  - `averageIntensity` (number, 0-10 scale?)
  - `totalDurationMinutes` (number)
- `chartData` (array):
  - `date`, `calories`, `weight`, `sessionCount`, `intensity`
- `breakdown` (array):
  - `exerciseName`, `frequency`, `maxWeight`, `trend` (up/flat/down)

**State:**
- `selectedPeriod`: "WEEK" | "MONTH" | "YEAR"
- `chartDataLoading`: boolean
- `chartDataError`: string | null

### Edge Cases & Errors
- **No data in period**: User has no workouts this week. Show "No workouts yet." Suggest "Start a workout to see stats."
- **Partial period**: If period is "last week" but only 3 days have passed, show what's available.
- **Timezone mismatch**: User's local timezone vs server UTC. Daily stats should group by user's local date, not UTC date.
- **Very large dataset**: Year view with data for every day. Chart rendering slow? Aggregate to weeks?
- **Network timeout**: Stats query takes 30s. Show loading spinner. Auto-retry? Timeout error after 60s?
- **Concurrent updates**: User adds workout while viewing stats. Does chart auto-refresh? Or show stale data until user refreshes?

### Unresolved Questions ❓
- [ ] What is "intensity"? Calculated from calories/time? User-entered? Formula?
- [ ] Should chart show multiple metrics (calories AND weight) on same graph, or separate tabs?
- [ ] Can user export stats as PDF or CSV?
- [ ] Should stats include body weight progression (if user logs weight)?
- [ ] Are "Personal Records" (highest weight per exercise) shown on this screen?
- [ ] What time range counts as "this week"? Monday-Sunday? Last 7 days? Calendar week?
- [ ] Can user compare two periods (e.g., this week vs. last week)?
- [ ] Should there be a goal setter (e.g., "I want to lift 10,000 kg this month")?

---

## Screen 4: Media Gallery / Exercise Photos

**Purpose:** Browse all photos/videos uploaded with exercises. Organized by date or exercise type.

### Components
- **Grid View**: Photos/videos in a grid (3 columns? 2?)
  - Thumbnail preview
  - Date overlay or badge
  - Play icon if video
- **Filter/Sort Options**:
  - Sort by: date (newest/oldest)
  - Filter by: exercise type? (or show all)
- **Tap Behavior**: Tap thumbnail to:
  - View full-screen
  - Play video
  - See associated exercise details
- **Actions on Media**:
  - [Share] (send to another app?)
  - [Delete]
  - [Download] (if applicable)
- **Empty State**: If no media yet, show "No photos yet. Add media to your exercises."

### User Actions
- **[Tap Thumbnail]**: Open full-screen viewer or video player
- **[Sort By]**: Changes grid order (newest first / oldest first)
- **[Filter By]**: Shows only media from specific exercise type (or all)
- **[Share Button]**: Allows sharing photo/video outside app (or just copy link?)
- **[Delete Button]**: Removes media file. Confirmation? "Delete this photo?"
- **[Close Full-Screen]**: Returns to grid view

### Data Model

**Inputs:**
- `userId`: Implicit (current user's media)
- `sortBy`: "date_asc" | "date_desc"
- `filterBy`: exerciseCategory | null (show all if null)

**Outputs/Display:**
- `mediaFiles` (array):
  - `id`, `fileName`, `filePath`, `mediaType` ("IMAGE" | "VIDEO"), `createdAt`, `exerciseId`, `exerciseName`
- `groupedByDate` (optional): Group photos by date
- `totalCount` (number): How many media files user has

**State:**
- `selectedMedia`: which media is currently full-screened?
- `sortOrder`: "recent" | "oldest"
- `filterExercise`: string | null

### Edge Cases & Errors
- **Slow loading**: User has 1000+ photos. Grid takes 10s to load. Pagination? Lazy loading?
- **File missing**: Media file deleted from storage but record exists in DB. Show broken image placeholder. Allow delete from DB?
- **Missing exercise data**: Media has `exerciseId` that no longer exists (exercise deleted). How to handle?
- **Very large video**: User uploaded 500MB video. Preview may be slow. Thumbnail only, no auto-play?
- **Unsupported format**: User somehow uploaded .exe file. Should app reject? Show error?
- **Duplicate filenames**: Two files named "workout.jpg". Conflict? Rename?

### Unresolved Questions ❓
- [ ] What size should thumbnails be? 100x100px? 200x200px?
- [ ] Should app show EXIF data (camera, ISO, etc.) for photos?
- [ ] Can user edit photos (crop, filter) within app?
- [ ] Should there be a "favorite" / "bookmark" feature for important form-check photos?
- [ ] Can user add notes/captions to photos?
- [ ] Is there a backup/cloud sync for photos?
- [ ] Can user view full-screen with slide-through (swipe left/right to see next photo)?
- [ ] Should deleted media be recoverable from a trash/recycle bin?

---

## Screen 5: Profile / Settings

**Purpose:** Manage user account information, app preferences, and data.

### Components
- **Profile Section**:
  - Profile photo (circular, tappable to change?)
  - Name (editable field)
  - Email (read-only or editable?)
- **Body Info Section**:
  - Height (cm) - editable
  - Weight (kg) - editable (current weight?)
  - Age - editable
- **Fitness Goals** (optional):
  - Goal statement or target (editable)
  - Current progress indicator
- **App Settings**:
  - Notifications (toggle on/off, configure frequency)
  - Dark mode (toggle)
  - Language (if multi-lang app)
  - Units preference (kg or lbs?)
- **Data Management**:
  - [Export Data] (download as CSV/JSON)
  - [Backup] (to cloud?)
  - [Delete Account] (destructive action)
- **About Section**:
  - App version
  - Terms of Service (link)
  - Privacy Policy (link)
- **Logout**:
  - [Log Out] button

### User Actions
- **[Edit Name/Height/Weight/Age]**: Inline editing or modal form?
- **[Change Profile Photo]**: Opens file picker or camera?
- **[Toggle Notification]**: Turns on/off? What granularity? (all notifications? per type?)
- **[Toggle Dark Mode]**: Switches theme immediately?
- **[Change Language]**: Reloads app in new language?
- **[Select Units]**: kg vs lbs (affects all weight displays across app?)
- **[Set Fitness Goal]**: Opens editor to set target weight, max lift, etc.?
- **[Export Data]**: Downloads file in chosen format
- **[Delete Account]**: Opens confirmation dialog ("All data will be permanently deleted. Type 'DELETE' to confirm.")
- **[Log Out]**: Clears session, returns to login

### Data Model

**Inputs:**
- `userId`: Implicit (current user)

**Outputs/Display:**
- `user` (object):
  - `id`, `email`, `name`, `heightCm`, `weightKg`, `age`, `createdAt`
- `preferences` (object):
  - `notificationsEnabled`, `darkModeEnabled`, `language`, `weightUnit`, `weightUnitDisplay` (string, "kg" or "lbs")
- `fitnessGoal` (object or null):
  - `targetWeight`, `targetCalories`, `targetWorkouts`, `deadline`
- `appVersion` (string): "1.0.0"

**State:**
- `profileEditing`: boolean (is user in edit mode?)
- `unsavedProfileChanges`: boolean
- `exportInProgress`: boolean
- `deleteConfirmationOpen`: boolean

### Edge Cases & Errors
- **Email not editable**: Should profile email be locked (can't change via app)?
- **Age = 0**: Valid? Error message? Validation on numeric fields?
- **Weight in two units**: User sets weightUnit to "lbs". Are all displays converted? Or stored as entered?
- **Profile photo size**: User uploads 50MB image. Compress? Reject? Limit?
- **Partial profile**: User hasn't set height/weight yet. Required or optional?
- **Export timeout**: Exporting 10 years of data takes 60s. Show progress bar? Cancel button?
- **Account deletion**: Are media files deleted? Workouts? Permanent or soft-delete?
- **Logout while exporting**: User logs out while data export in progress. Cancel export?

### Unresolved Questions ❓
- [ ] Can user change their email address? Is email verification required?
- [ ] Is there a password change screen (separate from profile)?
- [ ] Should app track weight progression over time (separate from "current weight")?
- [ ] Can user set multiple fitness goals or just one?
- [ ] Is there a "units" preference (kg/lbs) or does app default to user's location?
- [ ] Can user delete individual workouts from settings, or only from the Records screen?
- [ ] Is there a "data backup" feature (to cloud) or only manual export?
- [ ] Should settings be synced across devices, or local to this device?
- [ ] Is there a "training plan" or "routine" feature where user pre-defines workout structure?

---

## Summary

**Total Screens:** 5
- Home / Workout Dashboard
- Exercise Detail / Edit
- Statistics / Analytics
- Media Gallery / Exercise Photos
- Profile / Settings

**Critical Unresolved Questions:**
- ❓ **Session concurrency**: Can user have multiple active sessions (phone + web), or only one per user?
- ❓ **Timer behavior**: Does timer run in background when app is backgrounded? For how long?
- ❓ **Auto-save**: Should exercise edits auto-save or require explicit save?
- ❓ **Undo/redo**: Can user undo session actions (delete exercise, add set)?
- ❓ **Offline mode**: Can user log workouts offline, then sync when online?
- ❓ **Data synchronization**: If user logs in from multiple devices, is data real-time synced or eventual consistency?
- ❓ **Intensity metric**: How is "average intensity" calculated? Formula needed.
- ❓ **Media storage**: What's max file size? Max storage per user? Unlimited or quota?

**Cross-Cutting Concerns (Apply to All Screens):**
- **User isolation**: All data is user-specific. No screen should show another user's data.
- **Authentication**: All screens require valid JWT/session token. If token expires, redirect to login.
- **Navigation**: How do users move between screens? Tab bar? Hamburger menu? Back button behavior?
- **Loading states**: What skeleton or spinner is shown while data loads?
- **Error states**: Consistent error message format across all screens? Retry buttons?
- **Pagination**: List endpoints (exercises, workouts, media) support pagination. How many items per page? Load more or offset-based?
- **Timestamps**: All dates/times shown in user's local timezone (not UTC).
- **Responsiveness**: All screens work on mobile (375px width min). Tested orientations?

**Design Handoff Notes:**
- This spec is **design-ready**. No implementation decisions are included.
- The backend API spec should address all data fields and edge cases listed here.
- Frontend design should clarify: loading states, error messages, confirmation dialogs.
- Consider: Are there any screens **missing** from this spec? (e.g., login/signup, onboarding, exercise library selection?)
