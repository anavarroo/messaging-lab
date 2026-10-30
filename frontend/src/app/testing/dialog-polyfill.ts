/**
 * jsdom no implementa `showModal()` ni `close()` de `<dialog>`. Este apoyo, solo para las pruebas, los añade
 * con el comportamiento observable que importa: el atributo `open` y el evento `close`.
 */
export function installDialogPolyfill(): void {
  const proto = HTMLDialogElement.prototype;
  proto.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  proto.show = proto.showModal;
  proto.close = function (this: HTMLDialogElement, returnValue?: string) {
    if (!this.hasAttribute('open')) {
      return;
    }
    this.removeAttribute('open');
    if (returnValue !== undefined) {
      this.returnValue = returnValue;
    }
    this.dispatchEvent(new Event('close'));
  };
}

/** Lo que hace el navegador al pulsar Escape en un diálogo modal: evento `cancel` y, si no se evita, cierre. */
export function pressEscape(dialog: HTMLDialogElement): void {
  const cancel = new Event('cancel', { cancelable: true });
  dialog.dispatchEvent(cancel);
  if (!cancel.defaultPrevented) {
    dialog.close();
  }
}
