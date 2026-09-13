const copyButton = document.querySelector('[data-copy]');

const progressCast = document.querySelector('#progress-cast');
if (progressCast && window.AsciinemaPlayer) {
  AsciinemaPlayer.create('progress.cast', progressCast, {
    autoplay: true,
    loop: true,
    preload: true,
    terminalFontSize: 'small',
    fit: 'width'
  });
}

copyButton?.addEventListener('click', async () => {
  await navigator.clipboard.writeText(copyButton.dataset.copy);
  copyButton.textContent = 'copied';
  setTimeout(() => { copyButton.textContent = 'copy'; }, 1400);
});
