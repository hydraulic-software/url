const copyButton = document.querySelector('[data-copy]');

copyButton?.addEventListener('click', async () => {
  await navigator.clipboard.writeText(copyButton.dataset.copy);
  copyButton.textContent = 'copied';
  setTimeout(() => { copyButton.textContent = 'copy'; }, 1400);
});
