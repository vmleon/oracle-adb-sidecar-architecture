import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export type VersionsResponse = {
  adb: string;
  oracle: string;
  postgres: string;
  mongo: string;
};

@Injectable({ providedIn: 'root' })
export class VersionsService {
  private http = inject(HttpClient);

  fetch() {
    return this.http.get<VersionsResponse>('/api/v1/versions');
  }
}
