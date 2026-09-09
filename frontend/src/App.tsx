import { Route, Routes } from 'react-router'
import { CreateSessionScreen } from './routes/CreateSessionScreen'
import { JoinScreen } from './routes/JoinScreen'
import { LobbyScreen } from './routes/LobbyScreen'
import { ResultsScreen } from './routes/ResultsScreen'
import { SwipeScreen } from './routes/SwipeScreen'
import { WaitScreen } from './routes/WaitScreen'

// D-04's route table -- one route per screen. The root route creates a new session (there was no
// route for this at all until it was found missing against ROADMAP Phase 6 Success Criterion 1's
// "create/join a session ... entirely through the React SPA"). /s/:code/lobby is where a
// bootstrapped, not-yet-pinned participant actually lands (D-04's comment always called /s/:code
// itself the "join/lobby" route, but no code ever kept anyone there -- resolveScreen fell straight
// through to swipe). Every other route resolves to its real screen.
function App() {
  return (
    <Routes>
      <Route path="/" element={<CreateSessionScreen />} />
      <Route path="/s/:code" element={<JoinScreen />} />
      <Route path="/s/:code/lobby" element={<LobbyScreen />} />
      <Route path="/s/:code/swipe" element={<SwipeScreen />} />
      <Route path="/s/:code/wait" element={<WaitScreen />} />
      <Route path="/s/:code/results" element={<ResultsScreen />} />
    </Routes>
  )
}

export default App
