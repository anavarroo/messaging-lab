import { DOCUMENT, ViewportScroller } from '@angular/common';
import { Injectable, OnDestroy, inject } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';

/** Prefijo de las áreas del laboratorio. */
const LAB_PREFIX = '/rabbitmq/';

/** Cuántos fotogramas se espera, como máximo, a que el contenido de un área sea lo bastante alto para volver a su sitio. */
const MAX_FRAMES = 30;

/** Área del laboratorio a la que pertenece una dirección, o `null` si no es de ninguna (ni tiene ancla). */
export function areaOf(url: string): string | null {
  const path = url.split(/[?#]/)[0];
  if (!path.startsWith(LAB_PREFIX)) {
    return null;
  }
  return path.slice(LAB_PREFIX.length).split('/')[0] || null;
}

/**
 * Recuerda hasta dónde había bajado el usuario en cada área del laboratorio y lo devuelve a ese punto al volver.
 *
 * Al cambiar de pestaña la pantalla anterior se destruye y la página se acorta, así que sin esto el navegador
 * lleva siempre arriba del todo. Una navegación a un ancla (los enlaces del índice de la guía) no se toca: la
 * gestiona el propio enrutador. Una pestaña que se visita por primera vez empieza arriba.
 *
 * Se provee en la ruta del laboratorio: lo que recuerda se olvida al salir de él.
 */
@Injectable()
export class AreaScrollMemory implements OnDestroy {
  private readonly router = inject(Router);
  private readonly scroller = inject(ViewportScroller);
  private readonly window = inject(DOCUMENT).defaultView;

  private readonly positions = new Map<string, number>();
  private currentArea: string | null = areaOf(this.router.url);
  private pendingFrame: number | null = null;
  private readonly subscription: Subscription;

  constructor() {
    this.subscription = this.router.events
      .pipe(filter((event) => event instanceof NavigationStart || event instanceof NavigationEnd))
      .subscribe((event) => {
        if (event instanceof NavigationStart) {
          this.cancelRestore();
          this.remember();
        } else if (event instanceof NavigationEnd) {
          this.arrive(event.urlAfterRedirects);
        }
      });
  }

  /** Posición recordada de un área, o `undefined` si no se ha visitado. */
  positionOf(area: string): number | undefined {
    return this.positions.get(area);
  }

  ngOnDestroy(): void {
    this.cancelRestore();
    this.subscription.unsubscribe();
  }

  /** Guarda dónde estaba el usuario en el área que va a abandonar. */
  private remember(): void {
    if (this.currentArea) {
      this.positions.set(this.currentArea, this.scroller.getScrollPosition()[1]);
    }
  }

  private arrive(url: string): void {
    const area = areaOf(url);
    const changed = area !== this.currentArea;
    this.currentArea = area;
    const hasAnchor = url.includes('#');
    if (area && changed && !hasAnchor) {
      this.restore(this.positions.get(area) ?? 0);
    }
  }

  /**
   * Vuelve a la posición cuando el contenido ya se ha pintado. Si el área carga datos y todavía es corta, se
   * reintenta unos fotogramas: al llegar a la altura necesaria se queda ahí.
   */
  private restore(target: number): void {
    const view = this.window;
    if (!view) {
      this.scroller.scrollToPosition([0, target]);
      return;
    }
    let frames = 0;
    const step = () => {
      this.pendingFrame = null;
      this.scroller.scrollToPosition([0, target]);
      frames++;
      const reached = Math.abs(this.scroller.getScrollPosition()[1] - target) < 2;
      if (!reached && frames < MAX_FRAMES) {
        this.pendingFrame = view.requestAnimationFrame(step);
      }
    };
    this.pendingFrame = view.requestAnimationFrame(step);
  }

  private cancelRestore(): void {
    if (this.pendingFrame !== null) {
      this.window?.cancelAnimationFrame(this.pendingFrame);
      this.pendingFrame = null;
    }
  }
}
