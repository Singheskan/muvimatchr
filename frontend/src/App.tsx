import { Route, Routes } from 'react-router'
import { JoinScreen } from './routes/JoinScreen'

// D-04's route table. /s/:code/swipe, /s/:code/wait and /s/:code/results all map to JoinScreen
// for now -- later plans (06-02 through 06-05) replace those three targets with the real swipe
// deck, waiting screen and results screen. No client-side redirect from "/" into a session: the
// bare root just tells the user to open a session link.
function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePlaceholder />} />
      <Route path="/s/:code" element={<JoinScreen />} />
      <Route path="/s/:code/swipe" element={<JoinScreen />} />
      <Route path="/s/:code/wait" element={<JoinScreen />} />
      <Route path="/s/:code/results" element={<JoinScreen />} />
    </Routes>
  )
}

function HomePlaceholder() {
  return <p>Open the session link you were given to join a MuviMatchr session.</p>
}

export default App
