# Let's Workout Backend Specification

## Problem Statement

Fitness enthusiasts need a centralized platform to record, track, and analyze their workout activities. Without proper tracking, users cannot:
- Monitor progress over time
- Understand their training patterns
- Make data-driven adjustments to their routines
- Maintain consistency in their fitness journey

A backend service is needed to support mobile clients in managing workout sessions, exercises, sets, media files, and generating insights through statistics.

---

## Solution

Build a REST API backend that enables users to:
1. **Record workouts** — Create workout sessions with multiple exercises, sets, and performance metrics
2. **Track exercises** — Log individual exercises with weights, repetitions, and rest periods
3. **Store media** — Attach photos/videos to exercise records for form tracking
4. **Analyze progress** — View aggregated statistics (weekly, monthly, yearly) for insights
5. **Manage data** — Full CRUD operations with user-level data isolation

The backend uses:
- **Spring Boot 4** for REST API framework
- **PostgreSQL** for persistent storage
- **JOOQ** for type-safe SQL queries with performance guarantees (no N+1)
- **Spring Security** for user authentication and authorization
- **Flyway** for database migrations

---

## User Stories

### Workout Session Management
1. As a user, I want to create a new workout session with a start time, so that I can begin logging exercises
2. As a user, I want to add exercises to a session, so that I can record what I trained
3. As a user, I want to log multiple sets for each exercise (weight, reps, rest time), so that I can track progressive overload
4. As a user, I want to end a workout session and save total calories/weight lifted, so that I have a summary of my effort
5. As a user, I want to view all my past workout sessions in a paginated list, so that I can browse my history
6. As a user, I want to view detailed information about a specific workout, so that I can review what I did on that day
7. As a user, I want to edit a workout session (end time, totals), so that I can correct mistakes
8. As a user, I want to delete a workout session, so that I can remove unwanted or duplicate entries
9. As a user, I want only my own workouts to be visible to me, so that my data is private

### Exercise Tracking
10. As a user, I want to add exercise details (name, category like "chest", "legs"), so that I can organize my workouts by muscle groups
11. As a user, I want to track weight/load per set, so that I can see if I'm getting stronger
12. As a user, I want to track repetitions per set, so that I know how many reps I performed
13. As a user, I want to track rest time between sets, so that I can monitor recovery patterns
14. As a user, I want to delete exercises from a session, so that I can remove mistake entries

### Media Management
15. As a user, I want to upload photos/videos to an exercise record, so that I can document my form for later review
16. As a user, I want to support multiple file types (JPEG, PNG, MP4), so that I can use whichever format is convenient
17. As a user, I want to delete media files associated with exercises, so that I can manage storage
18. As a user, I want deleted media to be removed from both database and filesystem, so that I don't have orphaned files
19. As a user, I want to download previously uploaded media, so that I can review my form later

### Statistics & Analytics
20. As a user, I want to see weekly summary statistics (total sessions, calories, weight lifted), so that I can understand my weekly effort
21. As a user, I want to see monthly summary statistics, so that I can track longer-term progress
22. As a user, I want to see yearly summary statistics, so that I can review annual achievements
23. As a user, I want statistics to load quickly (single query, no N+1), so that the app feels responsive
24. As a user, I want statistics grouped by my local timezone, so that daily aggregations respect my location

### Data Isolation & Security
25. As a user, I want to authenticate and receive a token, so that I can access my data
26. As a user, I want all my API requests to require authentication, so that my data is protected
27. As a user, I want to be unable to access other users' workouts, so that privacy is enforced
28. As a user, I want my password to be securely hashed, so that my account is safe if the database is compromised

---

## Implementation Decisions

### API Layer (Controllers)

**Decision: Single entry point per resource**
- `WorkoutController` handles all `/api/v1/workouts` endpoints (CRUD, list, delete)
- `StatisticsController` handles `/api/v1/statistics` endpoints (weekly, monthly, yearly)
- `MediaController` handles `/api/v1/exercises/{id}/media` (upload, download, delete)

**Reasoning:** Reduces routing complexity, clear separation of concerns, easier to find related endpoints.

### Service Layer

**Decision: Services validate user ownership before operations**
- Every service method receives `userId` as a parameter
- Before modifying or retrieving data, service checks that the entity belongs to the user
- Throws `IllegalArgumentException` with "Unauthorized" message if mismatch detected

**Reasoning:** Centralizes authorization logic, prevents accidental data exposure, single source of truth for access control.

**Decision: Cascade delete with filesystem cleanup**
- When an exercise is deleted, all associated media files are deleted from database AND filesystem
- Implemented via `mediaFileRepository.deleteByExerciseId()` + file system loop
- If filesystem delete fails, database delete is rolled back

**Reasoning:** Prevents orphaned files consuming disk space, maintains database-filesystem consistency.

### Data Access Layer (JOOQ)

**Decision: Use JOOQ for all queries instead of Spring Data JPA**
- Repositories use `DSLContext` to build type-safe SQL
- Statistics queries use native JOOQ aggregation (SUM, COUNT) in single query
- No ORM lazy loading or N+1 risks

**Reasoning:** Type safety, performance (compile-time validation of column names), explicit control over query execution (no surprises).

**Decision: Statistics use aggregation in database, not application**
```sql
SELECT 
  COUNT(DISTINCT ws.id) as total_sessions,
  COALESCE(SUM(ws.total_calories), 0) as total_calories,
  COALESCE(SUM(ws.total_weight), 0) as total_weight
FROM workout_sessions ws
WHERE ws.user_id = ? AND DATE(ws.start_time) BETWEEN ? AND ?
```

**Reasoning:** Single query regardless of data volume, no N+1, microsecond response times.

### Storage

**Decision: Filesystem-based media storage**
- Uploaded files stored in `/var/lib/lets-workout/uploads` (configurable via `app.upload-dir`)
- File path stored in `media_files.file_path` column
- Filename prefixed with UUID to prevent collisions

**Reasoning:** Simple, no external dependencies (no S3 required), suitable for early-stage product. Migration to S3 possible later without API changes (just change storage backend).

### Database Schema

**Decision: Use PostgreSQL with simple schema**
- Tables: `users`, `workout_sessions`, `exercises`, `exercise_sets`, `media_files`
- Foreign keys with `ON DELETE CASCADE` for automatic cleanup
- Indexes on frequently-queried columns: `(user_id, start_time)` on sessions

**Reasoning:** PostgreSQL JSON support available if needed later, cascading constraints prevent orphaned records.

### Authentication & Authorization

**Decision: Spring Security with session-based authentication**
- User ID extracted from `SecurityContext` in each controller
- No JWT initially (simplifies implementation, suitable for internal API)
- Can upgrade to JWT/OAuth later if needed

**Reasoning:** Adequate for MVP, Spring Security handles authorization middleware, stateless suitable for REST.

### Pagination

**Decision: All list endpoints support `page` and `size` query parameters**
- `page`: 0-indexed (default: 0)
- `size`: items per page (default: 20, max: 100)
- Response includes total count for client-side pagination

**Reasoning:** Reduces payload size, predictable API contract, matches common patterns.

### Response Format

**Decision: All responses use snake_case JSON fields**
- DTO fields like `totalCalories` serialized as `total_calories`
- Configured via Jackson `PropertyNamingStrategies.SNAKE_CASE`

**Reasoning:** Consistency with REST API conventions, easier for frontend (JavaScript already uses camelCase).

---

## Testing Decisions

### What Makes a Good Test

- Tests external behavior, not implementation details
- Tests validate user-facing API contracts (request → response)
- Tests use real database (Testcontainers PostgreSQL), not mocks
- Tests verify access control (user cannot access other users' data)
- Tests verify cascade behavior (deleting session deletes exercises and media)

### Testing Strategy

**Seam: Controller Layer (REST API)**
- Single integration test suite: `WorkoutControllerIntegrationTest`
- Covers all CRUD operations and workflows
- Runs against real PostgreSQL container
- Tests verify: 201/200/204 status codes, response payloads, authorization checks

**Why this seam?**
- Controllers are the outermost API boundary — testing here validates the entire feature end-to-end
- Services and repositories are implicitly tested through controller tests
- No need for separate unit tests of services/repositories (would be redundant)

### Test Modules

1. **WorkoutControllerIntegrationTest**
   - `testCreateWorkoutSession()` — POST /api/v1/workouts succeeds, returns 201 + SessionDto
   - `testListWorkouts()` — GET /api/v1/workouts?page=0&size=20 returns Page<SessionDto>
   - `testGetWorkoutById()` — GET /api/v1/workouts/{id} returns session details
   - `testDeleteWorkoutCascadesMedia()` — DELETE /api/v1/workouts/{id} removes exercises and media
   - `testUnauthorizedAccessThrows()` — User cannot access another user's workout (401/403)
   - `testStatisticsMonthly()` — GET /api/v1/statistics/monthly returns correct aggregation
   - `testMediaUpload()` — POST /api/v1/exercises/{id}/media uploads file, returns MediaFile
   - `testMediaDownload()` — GET /api/v1/media/{id} retrieves file bytes
   - `testMediaDeleteRemovesFilesystem()` — DELETE /api/v1/media/{id} removes file from disk

2. **Setup**
   - `@SpringBootTest(webEnvironment = RANDOM_PORT)` for embedded server
   - `TestRestTemplate` for HTTP calls
   - Testcontainers PostgreSQL for real database
   - Each test creates test user via signup, receives auth token

### Prior Art

Similar integration test patterns exist in:
- Spring Boot guides (spring.io/guides/gs/testing-web/)
- Testcontainers documentation
- Project's existing `BackendApplicationTests.kt` (extend as template)

---

## Out of Scope

1. **User Profiles** — Setting profile photos, bio, personal stats. (Can add in future stories)
2. **Social Features** — Following users, sharing workouts, leaderboards. (Requires separate user graph)
3. **Real-time Updates** — WebSocket for live workout tracking. (Future enhancement)
4. **Advanced Analytics** — ML-based recommendations, form analysis via computer vision. (Out of scope)
5. **Mobile App** — Backend only; frontend implementation separate
6. **Payment/Monetization** — No subscription or premium features in scope
7. **Third-party Integrations** — No Strava, Apple Health, Google Fit sync
8. **Scheduled Jobs** — No email reports, backup jobs, or scheduled tasks
9. **Admin Dashboard** — No backend admin UI for managing users/data

---

## Further Notes

### Migration from REQUIREMENTS.md to This Spec

This spec translates the UI-focused requirements document into an implementation-ready specification:
- User stories expanded from implicit feature descriptions to explicit "As a user, I want..." format
- Implementation decisions document architectural choices not obvious from requirements
- Testing strategy clarifies what "done" means

### Deployment Notes

- Server listens on port 8080 by default (configurable via `server.port`)
- Database migrations run automatically on startup (Flyway)
- Upload directory must be writable by application user (Linux best practice: `/var/lib/lets-workout/uploads`)

### Performance Targets

- API response time < 500ms (p95)
- Statistics query < 50ms (native SQL aggregation)
- Pagination handles 1000+ sessions efficiently (indexed queries)
- Media upload supports files up to 50MB

### Security Considerations

- All timestamps stored as UTC; clients convert for display
- User IDs never logged in plaintext logs
- Media files stored outside webroot (not directly downloadable via HTTP)
- SQL injection prevented via JOOQ parameterized queries
- CSRF token validation for state-changing operations (Spring Security default)

### Future Enhancements

Once MVP is shipped:
1. Add JWT for mobile app authentication (separate from session auth)
2. Switch media storage to S3 (transparent to API consumers)
3. Add email notifications for milestones
4. Implement user search/social features
5. Add TypeScript/OpenAPI schema generation from code

