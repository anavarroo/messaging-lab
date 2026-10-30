import { ViewportScroller } from '@angular/common';

/**
 * Desplazamiento de la página simulado: jsdom no implementa `window.scrollTo`. Guarda la posición y, como en un
 * navegador, no deja bajar más de lo que mide el contenido (`maxScroll`).
 */
export class FakeViewportScroller extends ViewportScroller {
  position = 0;
  /** Lo más abajo que se puede llegar con el contenido que haya ahora. */
  maxScroll = Number.POSITIVE_INFINITY;
  readonly calls: number[] = [];

  override setOffset(): void {}

  override getScrollPosition(): [number, number] {
    return [0, this.position];
  }

  override scrollToPosition(position: [number, number] | readonly [number, number]): void {
    this.calls.push(position[1]);
    this.position = Math.min(position[1], this.maxScroll);
  }

  override scrollToAnchor(): void {}

  override setHistoryScrollRestoration(): void {}
}
