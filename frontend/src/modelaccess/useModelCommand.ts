import { useCommand } from '../api/useCommand';
import { definitiveRejection, errorMessage, identityFailure } from './errors';

/** Model commands retain the domain's explicit rejection policy and component-only secret lifetime. */
export function useModelCommand<T>(scope: () => string, succeeded: (result: T) => void, unauthorized: () => void = () => {}) {
  return useCommand(scope, succeeded, { definitiveRejection, errorMessage, identityFailure }, unauthorized);
}
