import { Route, Routes } from 'react-router'
import { JoinScreen } from './routes/JoinScreen'
import { SwipeScreen } from './routes/SwipeScreen'
import { WaitScreen } from './routes/WaitScreen'

// D-04's route table. /s/:code/results still maps to JoinScreen for now -- plan 06-05 replaces
// that target with the real results screen. No client-side redirect from "/" into a session: the
// bare root just tells the user to open a session link.
function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePlaceholder />} />
      <Route path="/s/:code" element={<JoinScreen />} />
      <Route path="/s/:code/swipe" element={<SwipeScreen />} />
      <Route path="/s/:code/wait" element={<WaitScreen />} />
      <Route path="/s/:code/results" element={<JoinScreen />} />
    </Routes>
  )
}

function HomePlaceholder() {
  return <p>Open the session link you were given to join a MuviMatchr session.</p>
}

export default App
