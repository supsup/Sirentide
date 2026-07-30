(() => {
  "use strict";

  const readyClass = "sirentide-frames-ready";
  let nextTargetNumber = 1;

  const conformantFrames = (wrapper) => {
    const classes = Array.from(wrapper.classList);
    const classShapeIsValid = classes.length === 1 && classes[0] === "sirentide"
      || classes.length === 2
        && classes[0] === "sirentide"
        && /^sirentide-[a-z]+$/.test(classes[1]);
    if (!classShapeIsValid) {
      return [];
    }
    const children = Array.from(wrapper.children);
    return children.length > 1 && children.every((child) => child.localName === "svg")
      ? children
      : [];
  };

  const allocateTargetId = () => {
    let candidate;
    do {
      candidate = `sirentide-frames-${nextTargetNumber}`;
      nextTargetNumber += 1;
    } while (document.getElementById(candidate));
    return candidate;
  };

  const fixedButton = (text) => {
    const button = document.createElement("button");
    button.type = "button";
    button.append(document.createTextNode(text));
    return button;
  };

  const enhance = (wrapper) => {
    if (wrapper.classList.contains(readyClass)) {
      return;
    }
    const frames = conformantFrames(wrapper);
    if (frames.length <= 1) {
      return;
    }

    const targetId = allocateTargetId();
    const controls = document.createElement("div");
    controls.classList.add("sirentide-frames-controls");

    const previous = fixedButton("Previous");
    previous.classList.add("sirentide-frames-previous");
    previous.setAttribute("aria-controls", targetId);

    const status = document.createElement("span");
    status.classList.add("sirentide-frames-status");
    status.setAttribute("aria-live", "polite");

    const next = fixedButton("Next");
    next.classList.add("sirentide-frames-next");
    next.setAttribute("aria-controls", targetId);

    controls.append(previous, status, next);
    let current = 0;
    const update = () => {
      frames.forEach((frame, index) => {
        frame.hidden = index !== current;
      });
      previous.disabled = current === 0;
      next.disabled = current === frames.length - 1;
      status.textContent = `Step ${current + 1} of ${frames.length}`;
    };

    previous.addEventListener("click", () => {
      if (current > 0) {
        current -= 1;
        update();
      }
    });
    next.addEventListener("click", () => {
      if (current + 1 < frames.length) {
        current += 1;
        update();
      }
    });

    try {
      wrapper.id = targetId;
      wrapper.append(controls);
      update();
      wrapper.classList.add(readyClass);
    } catch {
      frames.forEach((frame) => {
        frame.hidden = false;
      });
      controls.remove();
      wrapper.classList.remove(readyClass);
      if (wrapper.id === targetId) {
        wrapper.removeAttribute("id");
      }
    }
  };

  document.querySelectorAll(".sirentide").forEach(enhance);
})();
