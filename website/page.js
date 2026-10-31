"use strict";
function renderRecommendation() {
  const content = document.getElementById("recommendation");
  const error = document.getElementById("error");
  const action = document.getElementById("open-app");
  content.hidden = true;
  document.getElementById("open-web").href = "/app/";
  error.hidden = true;
  action.removeAttribute("href");
  document.getElementById("title").textContent = "Your next good listen.";
  document.getElementById("description").textContent = "Open a shared podcast or episode link to view it here.";
  document.getElementById("kind").textContent = "A recommendation for you";
  try {
    const item = Pod4MeLinks.parse(location.hash);
    if (!item) return;
    document.getElementById("kind").textContent = item.kind === "episode" ? "An episode for you" : "A podcast for you";
    document.getElementById("title").textContent = item.title || (item.kind === "episode" ? "A shared episode" : "A shared podcast");
    document.getElementById("description").textContent = "Someone thought you’d enjoy this. Continue in Pod4Me to make it your next listen.";
    document.getElementById("source").textContent = "From " + new URL(item.feed || item.url).hostname;
    action.href = Pod4MeLinks.intent(item);
    content.hidden = false;
    document.getElementById("open-web").href = "/app/" + location.hash;
  } catch (failure) {
    error.textContent = failure.message;
    error.hidden = false;
  }
}
window.addEventListener("hashchange", renderRecommendation);
renderRecommendation();
